package mpo.dayon.common.squeeze;

import java.io.IOException;

import mpo.dayon.common.buffer.MemByteBuffer;

public class NULL_Zipper extends Zipper {
	public MemByteBuffer zip(MemByteBuffer unzipped) throws IOException {
		final MemByteBuffer zipped = new MemByteBuffer();
		zipped.write(unzipped.getInternal(), 0, unzipped.size());
		return zipped;
	}

	public MemByteBuffer unzip(MemByteBuffer zipped) throws IOException {
		final MemByteBuffer unzipped = new MemByteBuffer();
		unzipped.write(zipped.getInternal(), 0, zipped.size());
		return unzipped;
	}
}
