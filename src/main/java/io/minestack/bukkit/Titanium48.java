package io.minestack.bukkit;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.mongodb.ServerAddress;
import com.rabbitmq.client.Address;
import io.minestack.db.Uranium;
import io.minestack.db.database.*;
import io.minestack.db.entity.UPlayer;
import io.minestack.db.entity.UServer;
import io.minestack.db.entity.UServerType;
import io.minestack.db.entity.UWorld;
import io.minestack.db.mongo.MongoDatabase;
import io.minestack.db.rabbitmq.RabbitMQ;
import org.bson.types.ObjectId;
import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class Titanium48 extends JavaPlugin {

    public UServer getMN2Server() {
        return Uranium.getServerLoader().loadEntity(new ObjectId(System.getenv("MY_SERVER_ID")));
    }

    @Override
    public void onEnable() {
        getLogger().info("Starting Titanium48");

        String hosts = System.getenv("MONGO_HOSTS");

        if (hosts == null) {
            getLogger().severe("MONGO_HOSTS is not set.");
            getServer().shutdown();
            return;
        }
        List<ServerAddress> mongoAddresses = new ArrayList<ServerAddress>();
        for (String host : hosts.split(",")) {

            String[] info = host.split(":");
            try {
                mongoAddresses.add(new ServerAddress(info[0], Integer.parseInt(info[1])));
                getLogger().info("Added Mongo Address " + host);
            } catch (UnknownHostException e) {
                getLogger().severe("Invalid Mongo Address " + host);
            }
        }

        hosts = System.getenv("RABBITMQ_HOSTS");
        String username = System.getenv("RABBITMQ_USERNAME");
        String password = System.getenv("RABBITMQ_PASSWORD");

        List<Address> rabbitAddresses = new ArrayList<>();
        for (String host : hosts.split(",")) {
            String[] info = host.split(":");
            try {
                rabbitAddresses.add(new Address(info[0], Integer.parseInt(info[1])));
            } catch (Exception e) {
                getLogger().severe("Invalid RabbitMQ Address " + host);
            }
        }

        Uranium.initDatabase(mongoAddresses, rabbitAddresses, username, password);

        UServer server = Uranium.getServerLoader().loadEntity(new ObjectId(System.getenv("MY_SERVER_ID")));
        if (server == null) {
            getLogger().severe("Could not find server data");
            getServer().shutdown();
            return;
        }

        UWorld world = server.getServerType().getDefaultWorld();
        getLogger().info("Setting up default world "+world.getName());
        WorldCreator worldCreator = new WorldCreator(world.getName());
        worldCreator.environment(org.bukkit.World.Environment.valueOf(world.getEnvironment().name()));
        if (world.getGenerator() != null) {
            worldCreator.generator(world.getGenerator());
        }

        DockerClientConfig.DockerClientConfigBuilder config = DockerClientConfig.createDefaultConfigBuilder();
        config.withVersion("1.13");
        config.withUri("http://" + server.getNode().getAddress() + ":4243");
        DockerClient dockerClient = new DockerClientImpl(config.build());
        InspectContainerResponse inspectResponse = dockerClient.inspectContainerCmd(server.getContainerId()).exec();
        getLogger().info(""+inspectResponse.getNetworkSettings().getPorts());
        for (ExposedPort exposedPort : inspectResponse.getNetworkSettings().getPorts().getBindings().keySet()) {
            if (exposedPort.getPort() == 25565) {
                int hostPort = inspectResponse.getNetworkSettings().getPorts().getBindings().get(exposedPort).getHostPort();
                server.setPort(hostPort);
                Uranium.getServerLoader().saveEntity(server);
                break;
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {
                getLogger().info("Killing Server");
                getServer().shutdown();
            }
        });

        getServer().getScheduler().runTaskTimer(this, () -> {
            UServer localServer = getMN2Server();
            if (localServer == null) {
                getLogger().severe("Couldn't find server data stopping server");
                getServer().shutdown();
                return;
            }
            if (localServer.getNode() == null) {
                getLogger().severe("Couldn't find node data stopping server");
                getServer().shutdown();
                return;
            }
            if (localServer.getServerType() == null) {
                getLogger().severe("Couldn't find type data stopping server");
                getServer().shutdown();
                return;
            }

            localServer.getPlayers().clear();
            for (Player player : Bukkit.getOnlinePlayers()) {
                UPlayer mn2Player = Uranium.getPlayerLoader().loadPlayer(player.getUniqueId());
                if (mn2Player != null) {
                    localServer.getPlayers().add(mn2Player);
                }
            }
            localServer.setLastUpdate(System.currentTimeMillis());
            Uranium.getServerLoader().saveEntity(localServer);
        }, 200L, 200L);
    }

    @Override
    public void onDisable() {
        getLogger().info("Stopping Titanium48");
        getServer().getScheduler().cancelAllTasks();
        UServer localServer = getMN2Server();
        localServer.setLastUpdate(0);
        Uranium.getServerLoader().saveEntity(localServer);
    }

}
