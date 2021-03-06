package com.faris.kingchat.client;

import com.faris.kingchat.core.Constants;
import com.faris.kingchat.core.helper.FXUtilities;
import com.faris.kingchat.core.helper.PacketType;
import com.faris.kingchat.core.helper.PrettyLogger;
import com.faris.kingchat.core.helper.Utilities;
import com.faris.kingchat.core.packets.*;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.net.SocketException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.logging.*;

public class ClientPane extends BorderPane implements Runnable {

	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM);

	private final ClientWindow window;
	private final String name;

	private JTextArea txtHistory;
	private JTextArea txtMessage;
	private OnlineUsersStage onlineUsersGUI;

	private Logger clientLogger;
	private ClientDataExchanger dataExchanger;

	private volatile boolean running = true;
	private Thread runningThread;
	private Thread receiveThread = null;
	private volatile boolean createdWindow = false;

	private boolean muted = false;

	public ClientPane(ClientWindow window, String name, String address, int port, String password) {
		this.window = window;

		this.name = name;
		this.clientLogger = PrettyLogger.createLogger("ClientLogger");
		try {
			this.clientLogger.log(Level.INFO, "Attempting to connect to '" + address + ":" + port + "'...");
			this.dataExchanger = new ClientDataExchanger(this.clientLogger, address, port);
		} catch (Exception ex) {
			this.clientLogger.log(Level.SEVERE, "Failed to connect to '" + address + ':' + port + "'", ex);
			ex.printStackTrace();
			System.exit(-1);
			return;
		}

		this.createWindow();
		long startTime = System.currentTimeMillis();
		while (!this.createdWindow) {
			if (System.currentTimeMillis() - startTime >= 5000L) {
				this.clientLogger.log(Level.SEVERE, "Took too long to create the window.");
				System.exit(-1);
				return;
			}
		}
		this.logLine("Connecting to " + address + ":" + port + " with user '" + name + "'...");

		this.onlineUsersGUI = new OnlineUsersStage();

		this.dataExchanger.setReadTimeout(5000);

		this.runningThread = new Thread(this, "Running");
		this.runningThread.start();

		PacketConnectionClient connectPacket = new PacketConnectionClient(name, password);
		this.dataExchanger.sendPacket(connectPacket, null, throwable -> {
			this.clientLogger.log(Level.SEVERE, "Failed to send connection packet to server", throwable);
			this.running = false;
			try {
				this.dataExchanger.close(false);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			System.exit(-1);
		});
	}

	public void initStage() {
		Stage stage = this.window.getStage();
		stage.setTitle(Constants.NAME + " Client");
		stage.setResizable(true);
		stage.setMinWidth(0D);
		stage.setMinHeight(0D);
		stage.setScene(new Scene(this, 880D, 550D));
		stage.setMinWidth(330D);
		stage.setMinHeight(225D);

		stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
			@Override
			public void handle(WindowEvent event) {
				if (!running) {
					Platform.exit();
					return;
				}
				if (onlineUsersGUI.isShowing()) onlineUsersGUI.close();
				if (dataExchanger != null) {
					if (dataExchanger.getUUID() != null) {
						long timestamp = System.currentTimeMillis();
						dataExchanger.sendPacket(new PacketDisconnectClient(dataExchanger.getUUID(), timestamp), this::disconnect, throwable -> {
							throwable.printStackTrace();
							this.disconnect();
						});
						return;
					}
				}
				this.disconnect();
				Platform.exit();
			}

			private void disconnect() {
				running = false;
				if (dataExchanger != null) {
					try {
						dataExchanger.close(false);
					} catch (Exception ignored) {
					}
				}
			}
		});
		stage.setOnHiding(event -> {
			if (onlineUsersGUI.isShowing()) onlineUsersGUI.hide();
		});
	}

	// Window-related methods

	private void createWindow() {
		this.createMenuBar();

		SwingNode swingNode = new SwingNode();
		SwingUtilities.invokeLater(() -> {
			JPanel contentPane = new JPanel();
			contentPane.setBorder(new EmptyBorder(10, 10, 10, 10));
			contentPane.setLayout(new BorderLayout(5, 5));
			this.populateContentPane(contentPane);

			swingNode.setContent(contentPane);

			this.txtMessage.requestFocus();

			this.createdWindow = true;
		});
		this.setCenter(swingNode);
	}

	private void createMenuBar() {
		Menu viewMenu = new Menu("View");
		MenuItem usersItem = new MenuItem("Users");
		usersItem.setOnAction(event -> {
			if (!this.onlineUsersGUI.isShowing()) {
				this.onlineUsersGUI.show();
				this.onlineUsersGUI.centerOnScreen();
			}
		});
		viewMenu.getItems().add(usersItem);

		Menu exitMenu = new Menu("Exit");
		MenuItem loginItem = new MenuItem("Login");
		loginItem.setOnAction(event -> {
			Runnable changeWindowRunnable = () -> {
				this.goToLogin(null, null);
			};
			if (this.dataExchanger != null) {
				if (this.dataExchanger.getUUID() != null) {
					long timestamp = System.currentTimeMillis();
					this.dataExchanger.sendPacket(new PacketDisconnectClient(this.dataExchanger.getUUID(), timestamp), changeWindowRunnable, throwable -> {
						throwable.printStackTrace();
						changeWindowRunnable.run();
					});
					return;
				}
			}
			changeWindowRunnable.run();
		});
		MenuItem closeItem = new MenuItem("Close");
		closeItem.setOnAction(event -> {
			if (this.running) {
				Runnable closeRunnable = () -> {
					this.running = false;
					if (this.dataExchanger != null) this.dataExchanger.shutdown();
					Platform.runLater(() -> this.window.getStage().close());
				};
				if (this.dataExchanger != null) {
					if (this.dataExchanger.getUUID() != null) {
						long timestamp = System.currentTimeMillis();
						try {
							this.dataExchanger.sendPacket(new PacketDisconnectClient(this.dataExchanger.getUUID(), timestamp), closeRunnable, throwable -> {
								throwable.printStackTrace();
								closeRunnable.run();
							});
							return;
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
				}
				closeRunnable.run();
			}
		});
		exitMenu.getItems().addAll(loginItem, closeItem);

		MenuBar menuBar = new MenuBar(viewMenu, exitMenu);
		this.setTop(menuBar);
	}

	private void populateContentPane(JPanel contentPane) {
		this.txtHistory = new JTextArea();
		this.txtHistory.setAutoscrolls(true);
		this.txtHistory.setLineWrap(true);
		this.txtHistory.setEditable(false);
		this.txtHistory.setComponentPopupMenu(new TextPopupMenu(this.txtHistory));
		JScrollPane scrollPane = new JScrollPane(this.txtHistory);
		contentPane.add(scrollPane, BorderLayout.CENTER);

		JPanel bottomBar = new JPanel();
		bottomBar.setLayout(new BorderLayout(5, 5));

		this.txtMessage = new JTextArea();
		this.txtMessage.setAutoscrolls(true);
		this.txtMessage.setLineWrap(true);
		this.txtMessage.addKeyListener(new KeyAdapter() {
			private boolean sendMessage = false;

			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					if (this.sendMessage) {
						doSend();
					}
				} else if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
					this.sendMessage = true;
				}
			}

			@Override
			public void keyReleased(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
					this.sendMessage = false;
				}
			}
		});
		this.txtMessage.setComponentPopupMenu(new TextPopupMenu(this.txtMessage));
		this.txtMessage.setEnabled(false);
		bottomBar.add(this.txtMessage, BorderLayout.CENTER);

		JButton btnSend = new JButton("Send");
		btnSend.addActionListener(e -> {
			this.doSend();
		});
		bottomBar.add(btnSend, BorderLayout.EAST);

		contentPane.add(bottomBar, BorderLayout.SOUTH);
	}

	// Class methods

	@Override
	public void run() {
		this.startListening();
	}

	public void close() {
		Runnable closeRunnable = () -> {
			this.running = false;
			if (this.receiveThread != null) this.receiveThread.interrupt();
			Platform.runLater(() -> this.window.getStage().close());
		};
		if (this.dataExchanger != null) {
			if (this.dataExchanger.getUUID() != null) {
				long timestamp = System.currentTimeMillis();
				try {
					this.dataExchanger.sendPacket(new PacketDisconnectClient(this.dataExchanger.getUUID(), timestamp), closeRunnable, throwable -> {
						throwable.printStackTrace();
						closeRunnable.run();
					});
					return;
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}
		closeRunnable.run();
	}

	private void doSend() {
		String message = this.txtMessage.getText().trim();
		long timestamp = System.currentTimeMillis();
		if (!message.isEmpty()) {
			if (message.length() > 2000) {
				this.logLine("Message length cannot exceed 2000 characters.");
				return;
			}
			this.txtMessage.setEnabled(false);
			try {
				this.dataExchanger.sendData(message, () -> {
					Platform.runLater(() -> {
						this.sendMessage(message, timestamp);
						this.txtMessage.setText("");
						this.txtMessage.setEnabled(true);
						this.txtMessage.requestFocus();
					});
				}, throwable -> this.clientLogger.log(Level.SEVERE, "Failed to send message '" + message + "'", throwable));
			} catch (Exception ex) {
				ex.printStackTrace();
				this.txtMessage.setEnabled(true);
				this.txtMessage.requestFocus();
			}
		}
	}

	public Logger getLogger() {
		return this.clientLogger;
	}

	private void goToLogin(String message, String title) {
		this.running = false;
		if (this.dataExchanger != null) {
			try {
				this.dataExchanger.close(true);
			} catch (Exception ignored) {
			}
		}
		Platform.runLater(() -> {
			try {
				LoginPane loginPane = new LoginPane(this.window);
				loginPane.initStage();

				this.window.getStage().centerOnScreen();
				if (message != null) {
					FXUtilities.createErrorDialog(message, "", title).showAndWait();
				}
			} catch (Exception ex) {
				this.clientLogger.log(Level.SEVERE, "Failed to go to the login screen", ex);
				System.exit(-1);
			}
		});
	}

	public void logLine(String message) {
		this.logLine(LocalDateTime.now(), message);
	}

	public void logLine(TemporalAccessor time, String message) {
		if (this.txtHistory == null) return;
		try {
			String timePrefix = '[' + DATE_TIME_FORMATTER.format(time) + ']';
			if (!this.txtHistory.getText().isEmpty()) {
				this.txtHistory.setText(this.txtHistory.getText() + System.lineSeparator());
			}
			this.txtHistory.setText(this.txtHistory.getText() + timePrefix + ' ' + message);
		} catch (Exception ex) {
			System.err.println("Failed to log message to console.");
			ex.printStackTrace();
			System.exit(-1);
		}
	}

	private void startListening() {
		this.receiveThread = new Thread(() -> {
			while (this.running) {
				try {
					String dataIn = this.dataExchanger.receiveData();
					try {
						String trimmedData = Utilities.trimData(dataIn);
						if (trimmedData == null) {
							this.clientLogger.severe("Received invalid data from " + this.dataExchanger.getAddress() + ":" + this.dataExchanger.getPort());
							if (this.dataExchanger.getReadTimeout() != 0) {
								this.dataExchanger.setReadTimeout(0);
							}
							continue;
						}
						String msgIn = new String(Base64.getDecoder().decode(trimmedData.getBytes()));
						if (this.dataExchanger.getReadTimeout() != 0) {
							this.dataExchanger.setReadTimeout(0);
						}
						if (msgIn.startsWith("{") && msgIn.endsWith("}")) {
							JsonObject jsonMessage = Utilities.getGson().fromJson(msgIn, JsonObject.class);
							if (jsonMessage.has("t")) {
								int packetType = jsonMessage.get("t").getAsInt();
								try {
									if (packetType == PacketType.Server.CONNECT) {
										PacketConnectionServer connectPacket = new PacketConnectionServer(jsonMessage);
										if (connectPacket.wasSuccessful()) {
											this.dataExchanger.setUUID(connectPacket.getUUID());
											this.muted = connectPacket.isMuted();
											this.clientLogger.log(Level.INFO, "Successfully connected to '" + this.dataExchanger.getAddress() + ":" + this.dataExchanger.getPort() + "'");

											Platform.runLater(() -> {
												this.logLine("Successfully connected to " + this.dataExchanger.getAddress() + ":" + this.dataExchanger.getPort());
												this.txtMessage.setEnabled(!this.muted);
												if (this.muted) {
													this.txtMessage.setToolTipText("You are muted!");
												}
											});
										} else {
											this.clientLogger.log(Level.SEVERE, "Failed to connect: " + connectPacket.getErrorMessage());
											this.goToLogin(connectPacket.getErrorMessage(), "Failed to connect");
											break;
										}
									} else if (packetType == PacketType.Server.MESSAGE_SEND) {
										PacketSendMessageServer messagePacket = new PacketSendMessageServer(jsonMessage);
										Platform.runLater(() -> {
											if (messagePacket.getName() != null) {
												this.logLine(Instant.ofEpochMilli(messagePacket.getTimestamp()).atZone(ZoneId.systemDefault()).toLocalDateTime(), messagePacket.getName() + ": " + messagePacket.getMessage());
											} else {
												this.logLine(Instant.ofEpochMilli(messagePacket.getTimestamp()).atZone(ZoneId.systemDefault()).toLocalDateTime(), "SERVER: " + messagePacket.getMessage());
											}
										});
									} else if (packetType == PacketType.Server.DISCONNECT) {
										PacketDisconnectServer disconnectPacket = new PacketDisconnectServer(jsonMessage);
										Platform.runLater(() -> {
											this.logLine(Instant.ofEpochMilli(disconnectPacket.getTimestamp()).atZone(ZoneId.systemDefault()).toLocalDateTime(), disconnectPacket.getName() + " disconnected.");
										});
									} else if (packetType == PacketType.Server.PING) {
										PacketPingServer pingPacket = new PacketPingServer(jsonMessage);
										PacketPingClient pingResponse = new PacketPingClient(this.dataExchanger.getUUID(), pingPacket.getTimestamp());
										this.dataExchanger.sendPacket(pingResponse);
									} else if (packetType == PacketType.Server.SHUTDOWN) {
										this.goToLogin("Server '" + this.dataExchanger.getAddress() + ":" + this.dataExchanger.getPort() + "' has shutdown.", "Server shutdown");
										break;
									} else if (packetType == PacketType.Server.KICK) {
										PacketKickServer kickPacket = new PacketKickServer(jsonMessage);
										if (this.dataExchanger.getUUID().equals(kickPacket.getUUID())) {
											this.goToLogin("You have been kicked!", "Disconnected");
											break;
										} else {
											Platform.runLater(() -> {
												this.logLine(Instant.ofEpochMilli(kickPacket.getTimestamp()).atZone(ZoneId.systemDefault()).toLocalDateTime(), kickPacket.getName() + " has been kicked from the server!");
											});
										}
									} else if (packetType == PacketType.Server.USER_LIST) {
										PacketUserListServer userListPacket = new PacketUserListServer(jsonMessage);
										Platform.runLater(() -> this.onlineUsersGUI.updateUsers(userListPacket.getUsers()));
									} else if (packetType == PacketType.Server.MUTE) {
										PacketMuteServer mutePacket = new PacketMuteServer(jsonMessage);
										Platform.runLater(() -> {
											this.logLine(Instant.now().atZone(ZoneId.systemDefault()).toLocalDateTime(), mutePacket.isMuted() ? "You have been muted." : "You are now unmuted.");
											this.txtMessage.setEnabled(!this.muted);
											if (this.muted) this.txtMessage.setToolTipText("You are muted!");
											else this.txtMessage.setToolTipText(null);
										});
									}
								} catch (Exception ex) {
									this.clientLogger.log(Level.SEVERE, "Failed to process packet data '" + dataIn + "'", ex);
								}
							}
						}
					} catch (Exception ignored) {
					}
				} catch (Exception ex) {
					if (ex instanceof SocketException) {
						if (ex.getMessage().equals("socket closed")) {
							this.running = false;
							break;
						}
					}
					this.clientLogger.log(Level.SEVERE, "Failed to receive data from server " + this.dataExchanger.getAddress() + ":" + this.dataExchanger.getPort(), ex);
					this.goToLogin(ex.getClass().getName() + ": " + ex.getMessage(), "Failed to receive data");
				}
			}
		}, "Receive") {
			@Override
			public void interrupt() {
				super.interrupt();
				dataExchanger.close(false);
			}
		};
		this.receiveThread.start();
	}

	private void sendMessage(String message, long timestamp) {
		if (message.isEmpty()) return;
		if (this.dataExchanger.getUUID() == null) return;
		this.dataExchanger.sendPacket(new PacketSendMessageClient(this.dataExchanger.getUUID(), message, timestamp));
	}

	// Subclasses

	private static class TextPopupMenu extends JPopupMenu {
		private final JTextComponent parentComponent;

		TextPopupMenu(JTextComponent textComponent) {
			this.parentComponent = textComponent;
			this.createItems();
		}

		private void createItems() {
			this.createCutItem();
			this.createCopyItem();
			this.createPasteItem();
			this.createClearItem();
		}

		private void createCutItem() {
			JMenuItem cutItem = new JMenuItem(new DefaultEditorKit.CutAction());
			cutItem.setText("Cut");
			cutItem.setMnemonic(KeyEvent.VK_X);
			this.add(cutItem);
		}

		private void createCopyItem() {
			JMenuItem copyItem = new JMenuItem(new DefaultEditorKit.CopyAction());
			copyItem.setText("Copy");
			copyItem.setMnemonic(KeyEvent.VK_C);
			this.add(copyItem);
		}

		private void createPasteItem() {
			JMenuItem pasteItem = new JMenuItem(new DefaultEditorKit.PasteAction());
			pasteItem.setText("Paste");
			pasteItem.setMnemonic(KeyEvent.VK_P);
			this.add(pasteItem);
		}

		private void createClearItem() {
			JMenuItem clearItem = new JMenuItem("Clear");
			clearItem.setMnemonic(KeyEvent.VK_Y);
			clearItem.addActionListener(e -> {
				parentComponent.setText("");
			});
			this.add(clearItem);
		}
	}

}
