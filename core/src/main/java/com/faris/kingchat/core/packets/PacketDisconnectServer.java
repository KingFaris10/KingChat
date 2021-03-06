package com.faris.kingchat.core.packets;

import com.faris.kingchat.core.helper.PacketType;
import com.google.gson.JsonObject;

import java.util.*;

public class PacketDisconnectServer implements Packet {

	private final String name;
	private UUID uuid;
	private final long timestamp;

	public PacketDisconnectServer(JsonObject jsonObject) {
		this.name = jsonObject.get("n").getAsString();
		this.uuid = UUID.fromString(jsonObject.get("u").getAsString());
		this.timestamp = jsonObject.get("w").getAsLong();
	}

	public PacketDisconnectServer(String name, UUID uuid, long timestamp) {
		this.name = name;
		this.uuid = uuid;
		this.timestamp = timestamp;
	}

	@Override
	public int getId() {
		return PacketType.Server.DISCONNECT;
	}

	public String getName() {
		return this.name;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public UUID getUUID() {
		return this.uuid;
	}

	@Override
	public JsonObject toJson() {
		JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("n", this.name);
		jsonObject.addProperty("u", this.uuid.toString());
		jsonObject.addProperty("w", this.timestamp);
		return jsonObject;
	}

}
