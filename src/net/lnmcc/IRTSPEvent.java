package net.lnmcc;

import java.io.IOException;
import java.nio.channels.SelectionKey;

public interface IRTSPEvent {
	void connect() throws IOException;

	void read(SelectionKey key) throws IOException;

	void write() throws IOException;
}
