package com.faris.kingchat.server.command.commands;

import com.faris.kingchat.server.Client;
import com.faris.kingchat.server.Server;
import com.faris.kingchat.server.command.ServerCommand;

import java.util.*;

public class CommandBan extends ServerCommand {

	public CommandBan(Server server, String label, String[] args) {
		super(server, label, args);
	}

	@Override
	public boolean onCommand() {
		if (this.args.length == 1) {
			String ipAddress = this.args[0];
			if (!com.faris.kingchat.server.helper.Utilities.IP_ADDRESS_PATTERN.matcher(ipAddress).matches()) {
				Client targetClient = this.server.getClient(this.args[0]);
				if (targetClient == null) {
					try {
						targetClient = this.server.getClient(UUID.fromString(this.args[0]));
					} catch (Exception ignored) {
					}
				}
				if (targetClient != null) {
					ipAddress = targetClient.getAddress().getHostName();
				} else {
					this.println("Unknown user: " + this.args[0]);
					return true;
				}
			}
			if (!ipAddress.contains(":")) {
				if (!this.server.getConfigManager().isBanned(ipAddress)) {
					List<Client> clientList = this.server.banIP(ipAddress);
					this.println("IP '" + ipAddress + "' has been banned. Kicked " + clientList.size() + " client" + (clientList.size() != 1 ? "s" : "") + ".");
				} else {
					this.server.getConfigManager().unbanIP(ipAddress);
					this.println("IP '" + ipAddress + "' has been unbanned.");
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public String getUsage() {
		return "<name|id|ip>";
	}

}
