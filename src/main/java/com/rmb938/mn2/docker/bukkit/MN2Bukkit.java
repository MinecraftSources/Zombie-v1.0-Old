package com.rmb938.mn2.docker.bukkit;

import com.github.dockerjava.client.DockerClient;
import com.github.dockerjava.client.command.InspectContainerResponse;
import com.github.dockerjava.client.model.ExposedPort;
import com.mongodb.ServerAddress;
import com.rabbitmq.client.Address;
import com.rmb938.mn2.docker.db.database.*;
import com.rmb938.mn2.docker.db.entity.MN2Player;
import com.rmb938.mn2.docker.db.entity.MN2Server;
import com.rmb938.mn2.docker.db.entity.MN2World;
import com.rmb938.mn2.docker.db.mongo.MongoDatabase;
import com.rmb938.mn2.docker.db.rabbitmq.RabbitMQ;
import org.bson.types.ObjectId;
import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class MN2Bukkit extends JavaPlugin {

    private ServerLoader serverLoader;
    private MN2Server server;

    @Override
    public void onEnable() {
        getLogger().info("Starting MN2 Bukkit");

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

        if (mongoAddresses.isEmpty()) {
            getLogger().severe("No valid mongo addresses");
            getServer().shutdown();
            return;
        }
        getLogger().info("Setting up mongo database mn2");
        MongoDatabase mongoDatabase = new MongoDatabase(mongoAddresses, "mn2");

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

        if (rabbitAddresses.isEmpty()) {
            getLogger().severe("No valid RabbitMQ addresses");
            return;
        }

        RabbitMQ rabbitMQ = null;
        try {
            getLogger().info("Setting up RabbitMQ " + username + " " + password);
            rabbitMQ = new RabbitMQ(rabbitAddresses, username, password);
        } catch (IOException e) {
            e.printStackTrace();
            getServer().shutdown();
            return;
        }

        PluginLoader pluginLoader = new PluginLoader(mongoDatabase);
        WorldLoader worldLoader = new WorldLoader(mongoDatabase);
        ServerTypeLoader serverTypeLoader = new ServerTypeLoader(mongoDatabase, pluginLoader, worldLoader);
        NodeLoader nodeLoader = new NodeLoader(mongoDatabase, new BungeeTypeLoader(mongoDatabase, pluginLoader, serverTypeLoader));
        serverLoader = new ServerLoader(mongoDatabase, nodeLoader, serverTypeLoader);

        server = serverLoader.loadEntity(new ObjectId(System.getenv("MY_SERVER_ID")));
        if (server == null) {
            getLogger().severe("Could not find server data");
            getServer().shutdown();
            return;
        }

        MN2World world = server.getServerType().getDefaultWorld();
        getLogger().info("Setting up default world "+world.getName());
        WorldCreator worldCreator = new WorldCreator(world.getName());
        worldCreator.environment(org.bukkit.World.Environment.valueOf(world.getEnvironment().name()));
        if (world.getGenerator() != null) {
            worldCreator.generator(world.getGenerator());
        }

        DockerClient dockerClient = new DockerClient("http://"+server.getNode().getAddress()+":4243");
        InspectContainerResponse inspectResponse = dockerClient.inspectContainerCmd(server.getContainerId()).exec();
        getLogger().info(""+inspectResponse.getNetworkSettings().getPorts());
        for (ExposedPort exposedPort : inspectResponse.getNetworkSettings().getPorts().getBindings().keySet()) {
            if (exposedPort.getPort() == 25565) {
                int hostPort = inspectResponse.getNetworkSettings().getPorts().getBindings().get(exposedPort).getHostPort();
                server.setPort(hostPort);
                serverLoader.saveEntity(server);
                break;
            }
        }

        getServer().getScheduler().runTaskTimer(this, () -> {
            MN2Server localServer = serverLoader.loadEntity(server.get_id());
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

            server.getPlayers().clear();
            for (Player player : Bukkit.getOnlinePlayers()) {
                MN2Player mn2Player = new MN2Player();
                mn2Player.setUuid(player.getUniqueId());
                mn2Player.setPlayerName(player.getName());
                mn2Player.setCurrentServer(server);
            }
            server.setLastUpdate(System.currentTimeMillis());
            serverLoader.saveEntity(server);
        }, 200L, 200L);
    }

    @Override
    public void onDisable() {
        getLogger().info("Stopping MN2 Bukkit");
        getServer().getScheduler().cancelAllTasks();

        server.setLastUpdate(0);
        serverLoader.saveEntity(server);
    }

}
