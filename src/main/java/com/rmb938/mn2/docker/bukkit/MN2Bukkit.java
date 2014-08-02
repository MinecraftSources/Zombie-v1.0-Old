package com.rmb938.mn2.docker.bukkit;

import com.mongodb.ServerAddress;
import com.rabbitmq.client.Address;
import com.rmb938.mn2.docker.db.database.NodeLoader;
import com.rmb938.mn2.docker.db.database.ServerLoader;
import com.rmb938.mn2.docker.db.database.ServerTypeLoader;
import com.rmb938.mn2.docker.db.entity.MN2Server;
import com.rmb938.mn2.docker.db.entity.MN2World;
import com.rmb938.mn2.docker.db.mongo.MongoDatabase;
import com.rmb938.mn2.docker.db.rabbitmq.RabbitMQ;
import org.bson.types.ObjectId;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class MN2Bukkit extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Starting MN2 Bukkit");

        getLogger().info("Unloading default world");
        getServer().unloadWorld(getServer().getWorlds().get(0), false);

        String hosts = System.getenv("MONGO_HOSTS");

        if (hosts == null) {
            getLogger().severe("MONGO_HOSTS is not set.");
            System.exit(0);
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
            System.exit(0);
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
            System.exit(0);
            return;
        }

        NodeLoader nodeLoader = new NodeLoader(mongoDatabase);
        ServerTypeLoader serverTypeLoader = new ServerTypeLoader(mongoDatabase);
        ServerLoader serverLoader = new ServerLoader(mongoDatabase, nodeLoader, serverTypeLoader);

        MN2Server server = serverLoader.loadEntity(new ObjectId(System.getenv("MY_SERVER_ID")));
        if (server == null) {
            getLogger().severe("Could not find server data");
            System.exit(0);
            return;
        }

        MN2World world = server.getServerType().getDefaultWorld();
        getLogger().info("Setting up default world "+world.getName());
        WorldCreator worldCreator = new WorldCreator(world.getName());
        worldCreator.environment(org.bukkit.World.Environment.valueOf(world.getEnvironment().name()));
        if (world.getGenerator() != null) {
            worldCreator.generator(world.getGenerator());
        }

    }

    @Override
    public void onDisable() {
        getLogger().info("Stopping MN2 Bukkit");
    }

}
