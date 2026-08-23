/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.audio;

import com.dunctebot.sourcemanagers.DuncteBotSources;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jmusicbot.utils.YouTubeUtil;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.nico.NicoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.lava.extensions.youtuberotator.YoutubeIpRotatorSetup;
import com.sedmelluq.lava.extensions.youtuberotator.planner.AbstractRoutePlanner;
import com.sedmelluq.lava.extensions.youtuberotator.planner.BalancingIpRoutePlanner;
import com.sedmelluq.lava.extensions.youtuberotator.planner.NanoIpRoutePlanner;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.IpBlock;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv4Block;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv6Block;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.Android;
import dev.lavalink.youtube.clients.AndroidMusic;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.Ios;
import dev.lavalink.youtube.clients.MWeb;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.TvHtml5Simply;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class PlayerManager extends DefaultAudioPlayerManager
{
    private final static Logger LOGGER = LoggerFactory.getLogger(PlayerManager.class);
    private final Bot bot;
    private final BotConfig config;

    public PlayerManager(Bot bot, BotConfig config)
    {
        this.bot = bot;
        this.config = config;
    }
    
    public void init()
    {
        TransformativeAudioSourceManager.createTransforms(bot.getConfig().getTransforms()).forEach(t -> registerSourceManager(t));
        
        YoutubeAudioSourceManager yt = setupYoutubeAudioSourceManager();
        if (config.getYTRoutingPlanner() != YouTubeUtil.RoutingPlanner.NONE)
        {
            AbstractRoutePlanner routePlanner = YouTubeUtil.createRouterPlanner(config.getYTRoutingPlanner(), config.getYTIpBlocks());
            YoutubeIpRotatorSetup rotator = new YoutubeIpRotatorSetup(routePlanner);
            
            rotator.forConfiguration(yt.getHttpInterfaceManager(), false)
                    .withMainDelegateFilter(yt.getContextFilter())
                    .setup();
        }

        yt.setPlaylistPageCount(bot.getConfig().getMaxYTPlaylistPages());
        registerSourceManager(yt);

        registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        registerSourceManager(new BandcampAudioSourceManager());
        registerSourceManager(new VimeoAudioSourceManager());
        try
        {
            registerSourceManager(new TwitchStreamAudioSourceManager());
        }
        catch(Exception e)
        {
            LOGGER.warn("Skipping Twitch source initialization because Twitch lookup failed during startup: {}", e.getMessage());
            LOGGER.debug("Twitch source initialization failure:", e);
        }
        registerSourceManager(new BeamAudioSourceManager());
        registerSourceManager(new GetyarnAudioSourceManager());
        registerSourceManager(new NicoAudioSourceManager());
        registerSourceManager(new HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY));

        AudioSourceManagers.registerLocalSource(this);

        DuncteBotSources.registerAll(this, "en-US");
    }

    private YoutubeAudioSourceManager setupYoutubeAudioSourceManager()
    {
        ClientOptions playbackOnly = new ClientOptions();
        playbackOnly.setSearching(false);

        YoutubeAudioSourceManager yt = new YoutubeAudioSourceManager(true,
                new Ios(playbackOnly),
                new Music(playbackOnly),
                new AndroidMusic(playbackOnly),
                new TvHtml5Simply(playbackOnly),
                new Tv(playbackOnly),
                new Web(),
                new MWeb(),
                new WebEmbedded(),
                new AndroidVr(playbackOnly),
                new Android(playbackOnly));
        yt.setPlaylistPageCount(bot.getConfig().getMaxYTPlaylistPages());

        // OAuth2 setup
        if (bot.getConfig().useYoutubeOauth2())
        {
            String token = null;
            try
            {
                token = Files.readString(OtherUtil.getPath("youtubetoken.txt"));
            }
            catch (NoSuchFileException e)
            {
                /* ignored */
            }
            catch (IOException e)
            {
                LOGGER.warn("Failed to read YouTube OAuth2 token file: {}", e.getMessage());
                return yt;
            }
            LOGGER.debug("Read YouTube OAuth2 refresh token from youtubetoken.txt");
            try
            {
                yt.useOauth2(token, false);
            }
            catch (Exception e)
            {
                LOGGER.warn("Failed to authorise with YouTube. If this issue persists, delete the youtubetoken.txt file to reauthorise.", e);
            }
        }
        return yt;
    }

    public Bot getBot()
    {
        return bot;
    }
    
    public boolean hasHandler(Guild guild)
    {
        return guild.getAudioManager().getSendingHandler()!=null;
    }
    
    public AudioHandler setUpHandler(Guild guild)
    {
        AudioHandler handler;
        if(guild.getAudioManager().getSendingHandler()==null)
        {
            AudioPlayer player = createPlayer();
            player.setVolume(bot.getSettingsManager().getSettings(guild).getVolume());
            handler = new AudioHandler(this, guild, player);
            player.addListener(handler);
            guild.getAudioManager().setSendingHandler(handler);
        }
        else
            handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        return handler;
    }
}
