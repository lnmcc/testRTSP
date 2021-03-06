package net.lnmcc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTSPClient extends Thread implements IRTSPEvent {

	private static final String VERSION = " RTSP/1.0\r\n";
	private static final String RTSP_OK = "RTSP/1.0 200 OK";

	private final InetSocketAddress remoteAddress;

	private SocketChannel socketChannel;

	private final ByteBuffer sendBuf;
	private final ByteBuffer receiveBuf;
	private static final int BUFFER_SIZE = 8192;

	private Selector selector;
	private String rtspAddress;
	private Status sysStatus;
	private String sessionId;
	private String trackInfo;

	private AtomicBoolean shutdown;
	private int seq = 1;
	private boolean isSent;

	private enum Status {
		init, options, describe, setup, play, pause, teardown, error, exit
	}

	public RTSPClient(InetSocketAddress remoteAddress, String rtspAddress) {
		this.remoteAddress = remoteAddress;
		this.rtspAddress = rtspAddress;

		sendBuf = ByteBuffer.allocateDirect(BUFFER_SIZE);
		receiveBuf = ByteBuffer.allocateDirect(BUFFER_SIZE);

		if (selector == null) {
			try {
				selector = Selector.open();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		sysStatus = Status.init;
		shutdown = new AtomicBoolean(false);
		isSent = false;

		startup();
	}

	public void startup() {
		try {
			socketChannel = SocketChannel.open();
			socketChannel.socket().setSoTimeout(5 * 60 * 1000);
			socketChannel.configureBlocking(false);
			socketChannel.connect(remoteAddress);
			socketChannel.register(selector, SelectionKey.OP_CONNECT
					| SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void send(byte[] out) {
		if (out == null || out.length < 1) {
			return;
		}

		synchronized (sendBuf) {
			sendBuf.clear();
			sendBuf.put(out);
			sendBuf.flip();
		}

		try {
			write();
			isSent = true;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public boolean isConnected() {
		return socketChannel != null && socketChannel.isConnected();
	}

	public byte[] receive() {
		if (isConnected()) {
			try {
				int len = 0;
				int readBytes = 0;

				synchronized (receiveBuf) {
					receiveBuf.clear();
					try {
						while ((len = socketChannel.read(receiveBuf)) > 0) {
							readBytes += len;
						}
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						receiveBuf.flip();
					}
					if (readBytes > 0) {
						final byte[] tmp = new byte[readBytes];
						receiveBuf.get(tmp);
						return tmp;
					} else {
						System.err.println("Receive empty data from server");
						return null;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			System.err.println("Client not Connected");
		}
		return null;
	}

	private void select() {
		int n = 0;

		try {
			if (selector == null) {
				return;
			}
			Thread.sleep(1000);
			n = selector.select(1000);
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (n > 0) {
			final Iterator<SelectionKey> iter = selector.selectedKeys()
					.iterator();
			while (iter.hasNext()) {
				final SelectionKey sk = iter.next();

				iter.remove();
				if (!sk.isValid()) {
					continue;
				}

				final IRTSPEvent handler = (IRTSPEvent) sk.attachment();
				try {
					if (sk.isConnectable()) {
						handler.connect();
					} else if (sk.isReadable()) {
						handler.read(sk);
					}
				} catch (IOException e) {
					e.printStackTrace();
					sk.cancel();
				}

			}

		}
	}

	public void shutdown() {
		if (isConnected()) {
			try {
				socketChannel.close();
				System.out.println("Client Shutdown");
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				socketChannel = null;
			}
		} else {
			System.err.println("Client not connected");
		}
	}

	@Override
	public void run() {
		while (!shutdown.get()) {
			try {
				if (isConnected() && (!isSent)) {
					switch (sysStatus) {
					case init:
						OptionCmd();
						break;
					case options:
						DescribeCmd();
						break;
					case describe:
						SetupCmd();
						break;
					case setup:
						PlayCmd();
						break;
					case play:
						PauseCmd();
						break;
					case pause:
						TeardownCmd();
						break;
					case teardown:
						shutdown.set(true);
						break;
					case error:
						System.err
								.println("Something error, Client will shutdown ...");
						shutdown.set(true);
					default:
						break;
					}
				}

				if (!shutdown.get()) {
					select();
				}

				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		shutdown();
	}

	public void handle(byte[] msg) {
		String tmp = new String(msg);
		System.out.println("Server >>>>\n" + tmp);

		if (tmp.startsWith(RTSP_OK)) {
			switch (sysStatus) {
			case init:
				sysStatus = Status.options;
				break;
			case options:
				if (tmp.indexOf("trackID") != -1) {
					trackInfo = tmp.substring(tmp.indexOf("trackID"));
				} else if (tmp.indexOf("streamid") != -1) {
					trackInfo = tmp.substring(tmp.indexOf("streamid"));
				} else {
					System.err.println("trackInfo error");
				}

				if (trackInfo != null || trackInfo.length() > 0) {
					sysStatus = Status.describe;
				} else {
					sysStatus = Status.error;
				}
				break;
			case describe:
				sessionId = tmp.substring(tmp.indexOf("Session: ") + 9,
						tmp.indexOf(";"));
				if (sessionId != null && sessionId.length() > 0) {
					sysStatus = Status.setup;
				} else {
					sysStatus = Status.error;
					System.err.println("SessionId error");
				}
				break;
			case setup:
				sysStatus = Status.play;
				break;
			case play:
				sysStatus = Status.pause;
				break;
			case pause:
				sysStatus = Status.teardown;
				break;
			case teardown:
				sysStatus = Status.exit;
			default:
				break;
			}
			isSent = false;
		} else {
			sysStatus = Status.error;
			System.err.println("Server error: " + tmp);
		}
	}

	@Override
	public void connect() throws IOException {
		if (isConnected()) {
			return;
		}

		do {
			socketChannel.finishConnect();
			if (!socketChannel.isConnected()) {
				try {
					Thread.sleep(1 * 1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		} while (!socketChannel.isConnected());
	}

	@Override
	public void read(SelectionKey key) throws IOException {
		final byte[] msg = receive();
		if (msg != null) {
			handle(msg);
		} else {
			key.cancel();
		}
	}

	@Override
	public void write() throws IOException {
		if (isConnected()) {
			try {
				socketChannel.write(sendBuf);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			System.err.println("Client not connected");
		}
	}

	private void TeardownCmd() {
		StringBuilder sb = new StringBuilder();
		sb.append("TEARDOWN ");
		sb.append(this.rtspAddress);
		sb.append(VERSION);
		sb.append("Cseq: ");
		sb.append(seq++);
		sb.append("\r\n");
		sb.append("User-Agent: VLC\r\n");
		sb.append("Session: ");
		sb.append(sessionId);
		sb.append("\r\n");
		sb.append("\r\n");
		System.out.println("Client >>>>\n" + sb.toString());
		send(sb.toString().getBytes());
	}

	private void PlayCmd() {
		StringBuilder sb = new StringBuilder();
		sb.append("PLAY ");
		sb.append(this.rtspAddress);
		sb.append(VERSION);
		sb.append("Session: ");
		sb.append(sessionId);
		sb.append("\r\n");
		sb.append("Cseq: ");
		sb.append(seq++);
		sb.append("\r\n");
		sb.append("\r\n");
		System.out.println("Client >>>>\n" + sb.toString());
		send(sb.toString().getBytes());
	}

	private void SetupCmd() {
		StringBuilder sb = new StringBuilder();
		sb.append("SETUP ");
		sb.append(this.rtspAddress);
		sb.append("/");
		sb.append(trackInfo);
		sb.append(VERSION);
		sb.append("Cseq: ");
		sb.append(seq++);
		sb.append("\r\n");
		sb.append("Transport: RTP/AVP;UNICAST;client_port=16264-16265;mode=play\r\n");
		sb.append("\r\n");
		System.out.println("Client >>>>\n" + sb.toString());
		send(sb.toString().getBytes());
	}

	private void OptionCmd() {
		StringBuilder sb = new StringBuilder();
		sb.append("OPTIONS ");
		sb.append(this.rtspAddress.substring(0,
				this.rtspAddress.lastIndexOf("/")));
		sb.append(VERSION);
		sb.append("Cseq: ");
		sb.append(seq++);
		sb.append("\r\n");
		sb.append("\r\n");
		System.out.println("Client >>>>\n" + sb.toString());
		send(sb.toString().getBytes());
	}

	private void DescribeCmd() {
		StringBuilder sb = new StringBuilder();
		sb.append("DESCRIBE ");
		sb.append(this.rtspAddress);
		sb.append(VERSION);
		sb.append("Cseq: ");
		sb.append(seq++);
		sb.append("\r\n");
		sb.append("\r\n");
		System.out.println("Client >>>>\n" + sb.toString());
		send(sb.toString().getBytes());
	}

	private void PauseCmd() {
		StringBuilder sb = new StringBuilder();
		sb.append("PAUSE ");
		sb.append(this.rtspAddress);
		sb.append("/");
		sb.append(VERSION);
		sb.append("Cseq: ");
		sb.append(seq++);
		sb.append("\r\n");
		sb.append("Session: ");
		sb.append(sessionId);
		sb.append("\r\n");
		sb.append("\r\n");
		System.out.println("Client >>>>\n" + sb.toString());
		send(sb.toString().getBytes());
	}

	public static void main(String[] args) {
		try {
			RTSPClient client = new RTSPClient(new InetSocketAddress(
					"192.168.2.184", 554), "rtsp://192.168.2.184:554/live.h264");
			client.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
