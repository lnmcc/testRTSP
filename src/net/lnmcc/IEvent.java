package net.lnmcc;

import java.io.IOException;
import java.nio.channels.SelectionKey;

public interface IEvent {
	void connect(SelectionKey key) throws IOException;

	void read(SelectionKey key) throws IOException;

	void write() throws IOException;
}
