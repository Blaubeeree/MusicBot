package com.jagrosh.jmusicbot.utils;

import com.sedmelluq.lava.extensions.youtuberotator.planner.*;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.IpBlock;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv4Block;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv6Block;
import com.sun.net.httpserver.HttpServer;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import org.json.JSONObject;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriverLogLevel;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.os.ExecutableFinder;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;

import io.github.bonigarcia.wdm.WebDriverManager;

public class YouTubeUtil {
    private final static Logger LOGGER = LoggerFactory.getLogger(YouTubeUtil.class);
    
    public enum RoutingPlanner {
        NONE,
        ROTATE_ON_BAN,
        LOAD_BALANCE,
        NANO_SWITCH,
        ROTATING_NANO_SWITCH
    }
    
    public static IpBlock parseIpBlock(String cidr) {
        if (Ipv6Block.isIpv6CidrBlock(cidr))
            return new Ipv6Block(cidr);

        if (Ipv4Block.isIpv4CidrBlock(cidr))
            return new Ipv4Block(cidr);
        
        throw new IllegalArgumentException("Could not parse CIDR " + cidr);
    }
    
    public static AbstractRoutePlanner createRouterPlanner(RoutingPlanner routingPlanner, List<IpBlock> ipBlocks) {
        
        switch (routingPlanner) {
            case NONE:
                return null;
            case ROTATE_ON_BAN:
                return new RotatingIpRoutePlanner(ipBlocks);
            case LOAD_BALANCE:
                return new BalancingIpRoutePlanner(ipBlocks);
            case NANO_SWITCH:
                return new NanoIpRoutePlanner(ipBlocks, true);
            case ROTATING_NANO_SWITCH:
                return new RotatingNanoIpRoutePlanner(ipBlocks);
            default:
                throw new IllegalArgumentException("Unknown RoutingPlanner value provided");
        }
    }

    public static void GetPoTokenAndVisitorData(String userChromePath, String userChromeDriverPath, boolean headless)
    {
        //
        // 1. Find a Chrome install & launch the WebDriver
        //
        WebDriverManager wdm = WebDriverManager.chromedriver();
        if (userChromePath != null) {
            wdm.browserVersionDetectionCommand(userChromePath + " --version");
        }
        else if (wdm.getBrowserPath().isEmpty())
        {
            // try finding a Chromium browser instead (Linux users usually install Chromium instead of Google Chrome)
            wdm = WebDriverManager.chromiumdriver();
            if (wdm.getBrowserPath().isEmpty()) {
                LOGGER.error("Could not obtain a PO token for YouTube playback, because no Chrome browser could be found. Please install Google Chrome or Chromium!");
                return;
            }
        }
        if (userChromeDriverPath == null)
            // Automatically download & set up ChromeDriver for the Chrome/Chromium version that the user has installed
            wdm.setup();
        else {
            // Find & use the ChromeDriver executable the user provided
            String path = new ExecutableFinder().find(userChromeDriverPath);
            if (path == null) {
                LOGGER.error("Could not obtain a PO token for YouTube playback, because the specified ChromeDriver could not be found. " +
                        "Please check your config's value of the chromedriverpath, or set it to \"AUTO\" to have JMusicBot automatically download ChromeDriver");
                return;
            }
            LOGGER.info("Using ChromeDriver at {}", path);
            System.setProperty("webdriver.chrome.driver", path);
        }

        ChromeOptions chromeOptions = new ChromeOptions();

        chromeOptions.setBinary(userChromePath != null ? userChromePath : wdm.getBrowserPath().get().toString());

        // Essential flags for headless/containerized environments (Docker)
        chromeOptions.addArguments("--no-sandbox");
        chromeOptions.addArguments("--disable-dev-shm-usage");
        chromeOptions.addArguments("--disable-gpu");
        chromeOptions.addArguments("--window-size=1920,1080");
        chromeOptions.addArguments("--disable-blink-features=AutomationControlled");
        chromeOptions.addArguments("--autoplay-policy=no-user-gesture-required");
        chromeOptions.addArguments("--disable-features=IsolateOrigins,site-per-process");
        chromeOptions.addArguments("--disable-site-isolation-trials");

        // Enable Chrome performance logging to capture Network.requestWillBeSent events
        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.PERFORMANCE, Level.ALL);
        chromeOptions.setCapability("goog:loggingPrefs", logPrefs);

        if (!headless)
            chromeOptions.addArguments("--auto-open-devtools-for-tabs");
        else
            chromeOptions.addArguments("--headless=new");

        ChromeDriverService.Builder chromeDriverBuilder = new ChromeDriverService.Builder();
        if (LOGGER.isDebugEnabled())
            chromeDriverBuilder.withLogLevel(ChromiumDriverLogLevel.DEBUG);

        WebDriver driver = new ChromeDriver(chromeDriverBuilder.build(), chromeOptions);

        //
        // 2. Start local HTTP server to host iframe embed & extract PO token from performance logs
        //
        HttpServer server = null;
        try {
            // Start a lightweight local HTTP server to host the YouTube embed in an iframe.
            // This provides a valid origin/Referer (http://127.0.0.1:PORT) so YouTube does not
            // reject the embed with Error 153 "Video player configuration error".
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/embed", exchange -> {
                String html = "<!DOCTYPE html>" +
                        "<html>" +
                        "<head>" +
                        "<meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">" +
                        "<style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;}" +
                        "iframe{width:100%;height:100%;border:none;}</style>" +
                        "</head>" +
                        "<body>" +
                        "<iframe id=\"player\" " +
                        "src=\"https://www.youtube-nocookie.com/embed/aqz-KE-bpKQ?autoplay=1&mute=1&enablejsapi=1\" " +
                        "referrerpolicy=\"strict-origin-when-cross-origin\" " +
                        "allow=\"autoplay; encrypted-media; picture-in-picture\" " +
                        "allowfullscreen></iframe>" +
                        "</body>" +
                        "</html>";
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.start();
            int serverPort = server.getAddress().getPort();

            // First, navigate to youtube.com to inject consent cookies to bypass EU/GDPR consent walls
            try {
                driver.get("https://www.youtube.com");
                driver.manage().addCookie(new Cookie.Builder("SOCS", "CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
                        .domain(".youtube.com")
                        .path("/")
                        .build());
                driver.manage().addCookie(new Cookie.Builder("CONSENT", "PENDING+999")
                        .domain(".youtube.com")
                        .path("/")
                        .build());
            } catch (Exception e) {
                LOGGER.debug("Could not pre-set consent cookies: {}", e.getMessage());
            }

            // Navigate to local embed wrapper page
            driver.get("http://127.0.0.1:" + serverPort + "/embed");

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            // Wait for iframe and switch to it to interact with player
            try {
                wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("player")));

                // Dismiss any consent dialog inside the iframe if present
                try {
                    List<WebElement> consentButtons = driver.findElements(By.xpath(
                            "//button[contains(@aria-label, 'Agree') or contains(@aria-label, 'Accept') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'agree') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'accept') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'alle akzeptieren') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'ich stimme zu') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'tout accepter') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'accepter tout') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'accetta tutto') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'aceptar todo')]"
                    ));
                    for (WebElement btn : consentButtons) {
                        if (btn.isDisplayed()) {
                            LOGGER.info("Dismissing consent dialog inside iframe...");
                            btn.click();
                            Thread.sleep(1000);
                            break;
                        }
                    }
                } catch (Exception ignored) {}

                // Try to click player inside iframe to trigger playback and /player request
                try {
                    WebElement moviePlayer = wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("#movie_player, .ytp-large-play-button, .html5-video-player, video")
                    ));
                    moviePlayer.click();
                } catch (Exception ignored) {
                    ((JavascriptExecutor) driver).executeScript(
                            "var p = document.getElementById('movie_player') || document.querySelector('video'); " +
                            "if (p) { if (p.playVideo) p.playVideo(); else if (p.play) p.play(); }"
                    );
                }

                // Switch back to default context so performance logging and main window operations work
                driver.switchTo().defaultContent();
            } catch (Exception e) {
                LOGGER.debug("Could not interact with embed iframe: {}", e.getMessage());
                try {
                    driver.switchTo().defaultContent();
                } catch (Exception ignored) {}
            }

            // Poll performance logs to capture the /youtubei/v1/player request
            boolean success = false;
            long deadline = System.currentTimeMillis() + 25_000;
            long nextActionTime = System.currentTimeMillis() + 5_000;

            while (System.currentTimeMillis() < deadline && !success) {
                LogEntries logEntries = driver.manage().logs().get(LogType.PERFORMANCE);
                for (LogEntry entry : logEntries) {
                    try {
                        JSONObject json = new JSONObject(entry.getMessage());
                        JSONObject message = json.optJSONObject("message");
                        if (message == null) continue;

                        String method = message.optString("method");
                        if ("Network.requestWillBeSent".equals(method)) {
                            JSONObject params = message.optJSONObject("params");
                            if (params == null) continue;
                            JSONObject req = params.optJSONObject("request");
                            if (req == null) continue;
                            String url = req.optString("url", "");
                            if (url.contains("/youtubei/v1/player")) {
                                String postData = req.optString("postData", null);

                                // In Chrome 130+, request payload may be in postDataEntries as Base64 bytes
                                if ((postData == null || postData.isEmpty()) && req.has("postDataEntries")) {
                                    org.json.JSONArray entries = req.optJSONArray("postDataEntries");
                                    if (entries != null) {
                                        StringBuilder sb = new StringBuilder();
                                        for (int i = 0; i < entries.length(); i++) {
                                            JSONObject entryObj = entries.optJSONObject(i);
                                            if (entryObj != null && entryObj.has("bytes")) {
                                                try {
                                                    byte[] decoded = java.util.Base64.getDecoder().decode(entryObj.getString("bytes"));
                                                    sb.append(new String(decoded, StandardCharsets.UTF_8));
                                                } catch (Exception ignored) {}
                                            }
                                        }
                                        if (sb.length() > 0) {
                                            postData = sb.toString();
                                        }
                                    }
                                }

                                if (postData == null || postData.isEmpty()) continue;

                                JSONObject body = new JSONObject(postData);

                                String visitorData = null;
                                if (body.has("context") && body.getJSONObject("context").has("client")) {
                                    visitorData = body.getJSONObject("context").getJSONObject("client").optString("visitorData", null);
                                }

                                String poToken = null;
                                if (body.has("serviceIntegrityDimensions")) {
                                    poToken = body.getJSONObject("serviceIntegrityDimensions").optString("poToken", null);
                                }

                                if (visitorData != null && poToken != null) {
                                    LOGGER.info("Successfully retrieved PO token & visitor data for YouTube playback.");
                                    LOGGER.debug("PO token: {}", poToken);
                                    LOGGER.debug("Visitor Data: {}", visitorData);

                                    // Set PO token and visitor data on both Web and WebEmbedded clients
                                    Web.setPoTokenAndVisitorData(poToken, visitorData);
                                    WebEmbedded.setPoTokenAndVisitorData(poToken, visitorData);
                                    success = true;
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Error parsing performance log entry: {}", e.getMessage());
                    }
                }

                if (success) break;

                // If still not captured after a while, try re-triggering play inside iframe
                if (System.currentTimeMillis() > nextActionTime) {
                    try {
                        driver.switchTo().frame("player");
                        ((JavascriptExecutor) driver).executeScript(
                                "var p = document.getElementById('movie_player') || document.querySelector('video'); " +
                                "if (p) { if (p.playVideo) p.playVideo(); else if (p.play) p.play(); }"
                        );
                        driver.switchTo().defaultContent();
                    } catch (Exception ignored) {}
                    nextActionTime = System.currentTimeMillis() + 5_000;
                }

                Thread.sleep(500);
            }

            if (!success) {
                saveDebugPage(driver);
                throw new Exception("Timed out waiting for YouTube player request. Current URL: " + driver.getCurrentUrl());
            }

        } catch (Exception e) {
            LOGGER.error("Failed to obtain PO token for YouTube playback: {}", e.getMessage());
            LOGGER.debug("Exception:", e);
        } finally {
            if (server != null) {
                try {
                    server.stop(0);
                } catch (Exception ignored) {}
            }
            // Only quit the browser when in headless mode. Helps with troubleshooting.
            if (headless)
                driver.quit();
        }
    }

    private static void saveDebugPage(WebDriver driver) {
        try {
            StringBuilder sb = new StringBuilder();
            String currentUrl = driver.getCurrentUrl();
            sb.append("<!-- Current URL: ").append(currentUrl).append(" -->\n");
            sb.append("<!-- MAIN PAGE SOURCE -->\n");
            try {
                sb.append(driver.getPageSource()).append("\n\n");
            } catch (Exception ignored) {}

            try {
                driver.switchTo().frame("player");
                sb.append("<!-- IFRAME SOURCE -->\n");
                sb.append(driver.getPageSource()).append("\n");
                driver.switchTo().defaultContent();
            } catch (Exception ignored) {}

            File debugFile = new File("youtube_debug.html");
            try (FileWriter writer = new FileWriter(debugFile)) {
                writer.write(sb.toString());
            }
            LOGGER.info("Saved YouTube debug page source to {} (Current URL: {})", debugFile.getAbsolutePath(), currentUrl);
        } catch (Exception e) {
            LOGGER.warn("Failed to save YouTube debug page source: {}", e.getMessage());
        }
    }
}
