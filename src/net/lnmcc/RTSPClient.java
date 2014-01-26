package net.lnmcc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTSPClient extends Thread implements IEvent {

	private static final String VERSION = " RTSP/1.0\r\n";
	private static final String RTSP_OK = "RTSP/1.0 200 OK";

	private final InetSocketAddress remoteAddress;
	private final InetSocketAddress localAddress;

	private SocketChannel socketChannel;

	private final ByteBuffer sendBuf;
	private final ByteBuffer receiveBuf;

	private static final int BUFFER_SIZE = 8129;

	private Selector selector;
	private String address;
	private Status sysStatus;
	private String sessionId;

	private AtomicBoolean shutdown;
	private int seq = 1;
	private boolean isSent;
	private String trackInfo;

	private enum Status {
		init, options, describe, setup, play, pause, teardown
	}

	public RTSPClient(InetSocketAddress remoteAddress,
			InetSocketAddress localAddress, String address) {
		this.remoteAddress = remoteAddress;
		this.localAddress = localAddress;
		this.address = address;

		sendBuf = ByteBuffer.allocateDirect(BUFFER_SIZE);
		receiveBuf = ByteBuffer.allocateDirect(BUFFER_SIZE);

		if (selector == null) {
			try {
				selector = Selector.open();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		startup();
		sysStatus = Status.init;
		shutdown = new AtomicBoolean(false);
		isSent = false;
	}

	public void startup() {
		try {
			socketChannel = SocketChannel.open();
			socketChannel.socket().setSoTimeout(30000);
			socketChannel.configureBlocking(false);
			socketChannel.socket().bind(localAddress);

			if (socketChannel.connect(remoteAddress)) {
				System.out.println("Begin to connect: " + remoteAddress);
			}
			
			socketChannel.register(selector, SelectionKey.OP_CONNECT
					| SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
			
			System.out.println("Open succeed");
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
						System.out.println("Receive empty data");
						return null;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			System.out.println("Not Connected");
		}
		return null;
	}

	private void select() {
		int n = 0;

		try {
			if (selector == null) {
				return;
			}
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

				final IEvent handler = (IEvent) sk.attachment();
				try {
					if (sk.isConnectable()) {
						handler.connect(sk);
					} else if (sk.isReadable()) {
						handler.read(sk);
					} else {
						System.out.print("handle event error");
					}
				} catch (IOException e) {
					e.printStackTrace();
					handler.error(e);
					sk.cancel();
				}

			}

		}
	}

	public void shutdown() {
		if (isConnected()) {
			try {
				socketChannel.close();
				System.out.println("closed");
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				socketChannel = null;
			}
		} else {
			System.out.println("Alreadly disconected");
		}
	}

	@Override
	public void run() {
		while (!shutdown.get()) {
			try {
				if (isConnected() && (!isSent)) {
					switch (sysStatus) {
					case init:
						doOption();
						break;
					case options:
						doDescribe();
						break;
					case describe:
						doSetup();
						break;
					case setup:
						if (sessionId == null && sessionId.length() > 0) {
							System.out.println("setup not return");
						} else {
							doPlay();
						}
						break;
					case play:
						doPause();
						break;
					case pause:
						doTeardown();
						break;
					default:
						break;
					}
				} 

				select();

				try {
					Thread.sleep(1000);
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
		System.out.println("return msg: \n" + tmp);

		if (tmp.startsWith(RTSP_OK)) {
			switch (sysStatus) {
			case init:
				sysStatus = Status.options;
				System.out.println("option ok");
				break;
			case options:
				sysStatus = Status.describe;
				trackInfo = tmp.substring(tmp.indexOf("trackID"));
				System.out.println("describ ok");
				break;
			case describe:
				sessionId = tmp.substring(tmp.indexOf("Session: ") + 9,
						tmp.indexOf(";"));
				if (sessionId != null && sessionId.length() > 0) {
					sysStatus = Status.setup;
					System.out.println("setup ok");
				}
				break;
			case setup:
				sysStatus = Status.play;
				System.out.println("play ok");
				break;
			case play:
				sysStatus = Status.pause;
				System.out.println("pause ok");
				break;
			case pause:
				sysStatus = Status.teardown;
				System.out.println("teardown ok");
				break;
			case teardown:
				sysStatus = Status.init;
				System.out.println("exit start");
			default:
				break;
			}
			isSent = false;
		} else {
			System.out.println("return error" + tmp);
		}
	}

	@Override
	public void connect(SelectionKey key) throws IOException {
		if (isConnected()) {
			return;
		}
		socketChannel.finishConnect();
		while (!socketChannel.isConnected()) {
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			socketChannel.finishConnect();
		}
	}
	
	 private void doTeardown() {
	        StringBuilder sb = new StringBuilder();
	        sb.append("TEARDOWN ");
	        sb.append(this.address);
	        sb.append("/");
	        sb.append(VERSION);
	        sb.append("Cseq: ");
	        sb.append(seq++);
	        sb.append("\r\n");
	        sb.append("User-Agent: RealMedia Player HelixDNAClient/10.0.0.11279 (win32)\r\n");
	        sb.append("Session: ");
	        sb.append(sessionId);
	        sb.append("\r\n");
	        send(sb.toString().getBytes());
	        System.out.println(sb.toString());
	    }

	    private void doPlay() {
	        StringBuilder sb = new StringBuilder();
	        sb.append("PLAY ");
	        sb.append(this.address);
	        sb.append(VERSION);
	        sb.append("Session: ");
	        sb.append(sessionId);
	        sb.append("Cseq: ");
	        sb.append(seq++);
	        sb.append("\r\n");
	        sb.append("\r\n");
	        System.out.println(sb.toString());
	        send(sb.toString().getBytes());

	    }

	    private void doSetup() {
	        StringBuilder sb = new StringBuilder();
	        sb.append("SETUP ");
	        sb.append(this.address);
	        sb.append("/");
	        sb.append(trackInfo);
	        sb.append(VERSION);
	        sb.append("Cseq: ");
	        sb.append(seq++);
	        sb.append("\r\n");
	        sb.append("Transport: RTP/AVP;UNICAST;client_port=16264-16265;mode=play\r\n");
	        sb.append("\r\n");
	        System.out.println(sb.toString());
	        send(sb.toString().getBytes());
	    }

	    private void doOption() {
	        StringBuilder sb = new StringBuilder();
	        sb.append("OPTIONS ");
	        sb.append(this.address.substring(0, address.lastIndexOf("/")));
	        sb.append(VERSION);
	        sb.append("Cseq: ");
	        sb.append(seq++);
	        sb.append("\r\n");
	        sb.append("\r\n");
	        System.out.println(sb.toString());
	        send(sb.toString().getBytes());
	    }

	    private void doDescribe() {
	        StringBuilder sb = new StringBuilder();
	        sb.append("DESCRIBE ");
	        sb.append(this.address);
	        sb.append(VERSION);
	        sb.append("Cseq: ");
	        sb.append(seq++);
	        sb.append("\r\n");
	        sb.append("\r\n");
	        System.out.println(sb.toString());
	        send(sb.toString().getBytes());
	    }
	    
	    private void doPause() {
	        StringBuilder sb = new StringBuilder();
	        sb.append("PAUSE ");
	        sb.append(this.address);
	        sb.append("/");
	        sb.append(VERSION);
	        sb.append("Cseq: ");
	        sb.append(seq++);
	        sb.append("\r\n");
	        sb.append("Session: ");
	        sb.append(sessionId);
	        sb.append("\r\n");
	        send(sb.toString().getBytes());
	        System.out.println(sb.toString());
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
			System.out.println("Channel is null or disconnected");
		}
	}

	@Override
	public void error(Exception e) {
		e.printStackTrace();
	}

	public static void main(String[] args) {
		try {
			RTSPClient client = new RTSPClient(new InetSocketAddress(
					"192.168.2.191", 554), new InetSocketAddress(
					"192.168.2.50", 0),
					"rtsp://192.168.2.191:554/user=admin&password=admin&channel=1&stream=0.sdp");
			client.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
