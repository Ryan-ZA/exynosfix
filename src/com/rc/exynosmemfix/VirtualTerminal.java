package com.rc.exynosmemfix;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import android.util.Log;

public class VirtualTerminal {

	public static VirtualTerminal instance_su, instance_sh;
	private static int retries;

	public static VTCommandResult run(String command, VirtualTerminal instance) {
		return run(command, instance, null);
	}

	public static synchronized VTCommandResult run(String command, boolean asroot) {
		VirtualTerminal instance;
		int count = 0;
		while (count < 5) {
			try {
				if (asroot) {
					if (instance_su == null || instance_su.shutdown) {
						instance_su = new VirtualTerminal(asroot);
					}
					instance = instance_su;
				} else {
					if (instance_sh == null || instance_sh.shutdown) {
						instance_sh = new VirtualTerminal(asroot);
					}
					instance = instance_sh;
				}
				return run(command, instance, null);
			} catch (IOException e1) {
				e1.printStackTrace();
			} catch (InterruptedException e2) {
				e2.printStackTrace();
			}
			count++;
		}
		return null;
	}

	public static VTCommandResult run(String command, VirtualTerminal terminal, VTCallback callback) {
		retries = 0;
		try {
			// return run_internal(command, Helpers.isRootAvailable() ? asroot :
			// false, callback);
			return run_internal(command, terminal, callback);
		} catch (Exception ex) {
			ex.printStackTrace();
			return new VTCommandResult(1, "", ex.getLocalizedMessage());
		}
	}
	private static VTCommandResult run_internal(String command, VirtualTerminal instance, VTCallback callback) throws Exception {
		try {
			return instance.runCommand(command, callback);
		} catch (BrokenPipeException bpe) {
			instance.shutdown();

			bpe.printStackTrace();
			retries++;
			if (retries > 5)
				throw new Exception("Max retries for VirtualTerminal reached!");
			Thread.sleep(500);
			return run_internal(command, instance, callback);
		} catch (Exception ex) {
			ex.printStackTrace();
			instance.shutdown();
			throw new Exception("Error in VirtualTerminal", ex);
		}
	}

	public static void Destroy() {
		if (instance_su != null && !instance_su.shutdown) {
			instance_su.shutdown();
		}
		if (instance_sh != null && !instance_sh.shutdown) {
			instance_sh.shutdown();
		}
		instance_su = null;
		instance_sh = null;
	}

	DataOutputStream toProcess;
	final Object ReadLock = new Object();
	final Object WriteLock = new Object();

	ByteArrayOutputStream inpbuffer = new ByteArrayOutputStream();
	ByteArrayOutputStream errbuffer = new ByteArrayOutputStream();

	InputReader inpreader;
	InputReader errreader;
	boolean shutdown;
	final static String TAGC = "VTC";
	final static String TAGR = "VTR";

	public CircularFifoBuffer<String> logbuffer = new CircularFifoBuffer<String>(200);
	
	public boolean isShutdown() {
		return shutdown;
	}

	public VirtualTerminal(boolean asroot) throws IOException, InterruptedException {
		Process process = null;
		if (asroot)
			process = Runtime.getRuntime().exec("su");
		else
			process = Runtime.getRuntime().exec("sh");
		toProcess = new DataOutputStream(process.getOutputStream());

		inpreader = new InputReader(process.getInputStream(), inpbuffer);
		errreader = new InputReader(process.getErrorStream(), errbuffer);

		Thread.sleep(5);

		inpreader.start();
		errreader.start();
	}

	// public VTCommandResult busybox(String command) throws Exception {
	// return runCommand(command);
	// }

	// public void busyboxFNF(String command) throws Exception {
	// FNF("busybox "+command);
	// }

	public VTCommandResult runCommand(String command, VTCallback callback) throws Exception {
		if (shutdown) {
			return new VTCommandResult(1, "VirtalTerminal was already shutdown.", "VirtalTerminal was already shutdown.");
		}

		StringBuilder inpStringBuilder = new StringBuilder();
		StringBuilder errStringBuilder = new StringBuilder();

		Log.i(TAGC, command);
		synchronized (WriteLock) {
			inpbuffer.reset();
			errbuffer.reset();
		}

		logbuffer.add("<font color='#0000bb'>" + command + "</font><br />");
		toProcess.writeBytes(command + "; echo :RET=$?");
		toProcess.writeBytes("\n");
		toProcess.flush();
		while (!shutdown) {
			synchronized (ReadLock) {
				boolean doWait;
				synchronized (WriteLock) {
					byte[] inpbyte = inpbuffer.toByteArray();
					String inp = new String(inpbyte);
					Log.i(TAGR, inp);
					inpStringBuilder.append(inp);
					inpbuffer.reset();
					doWait = !inpStringBuilder.toString().contains(":RET=");
					if (callback != null) {
						callback.onProgress(inpStringBuilder, errStringBuilder, toProcess);
					}
				}
				if (doWait) {
					ReadLock.wait();
				}
			}
			synchronized (WriteLock) {
				byte[] inpbyte = inpbuffer.toByteArray();
				byte[] errbyte = errbuffer.toByteArray();

				String tinp = new String(inpbyte);
				String terr = new String(errbyte);

				Log.i(TAGR, tinp);

				inpStringBuilder.append(tinp);
				inpbuffer.reset();
				errStringBuilder.append(terr);
				errbuffer.reset();

				String inp = inpStringBuilder.toString();
				String err = errStringBuilder.toString();

				if (callback != null) {
					callback.onProgress(inpStringBuilder, errStringBuilder, toProcess);
				}

				if (err.contains("Permission denied"))
					throw new BrokenPipeException();

				if (inp.contains(":RET=")) {
					if (inp.contains(":RET=EOF") || err.contains(":RET=EOF"))
						throw new BrokenPipeException();
					if (inp.contains(":RET=0")) {
						if (inp.length() < 8)
							inp = "";
						else
							inp = inp.substring(0, inp.length() - 8);
						log(inp, "#999999");
						log(err, "#999999");
						return new VTCommandResult(0, inp, err);
					} else {
						log(inp, "#999999");
						log(err, "#999999");
						Log.i(TAGR, err);
						return new VTCommandResult(1, inp, err);
					}
				}
			}
		}
		return new VTCommandResult(-1);
	}

	public void shutdown() {
		shutdown = true;
		inpreader.interrupt();
		errreader.interrupt();
		try {
			toProcess.close();
			inpreader.is.close();
			errreader.is.close();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public class InputReader extends Thread {

		InputStream is;
		ByteArrayOutputStream baos;

		public InputReader(InputStream is, ByteArrayOutputStream baos) {
			this.is = is;
			this.baos = baos;
		}

		@Override
		public void run() {
			try {
				byte[] buffer = new byte[1024];
				while (!shutdown) {
					int read = is.read(buffer);
					if (read < 0) {
						synchronized (WriteLock) {
							buffer = ":RET=EOF".getBytes();
							baos.write(buffer);
						}
						synchronized (ReadLock) {
							ReadLock.notifyAll();
						}
						return;
					}
					if (read > 0) {
						synchronized (WriteLock) {
							baos.write(buffer, 0, read);
						}
						synchronized (ReadLock) {
							ReadLock.notifyAll();
						}
					}
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	public static class VTCommandResult {
		public final String stdout;
		public final String stderr;
		public final Integer exit_value;

		VTCommandResult(Integer exit_value_in, String stdout_in, String stderr_in) {
			exit_value = exit_value_in;
			stdout = stdout_in;
			stderr = stderr_in;
		}

		VTCommandResult(Integer exit_value_in) {
			this(exit_value_in, null, null);
		}

		public boolean success() {
			return exit_value != null && exit_value == 0;
		}
	}

	public static class BrokenPipeException extends Exception {
		private static final long serialVersionUID = 1L;

		public BrokenPipeException() {
		}
	}

	public interface VTCallback {
		public void onProgress(StringBuilder inp, StringBuilder err, DataOutputStream out) throws IOException;
	}

	private void log(String tmp, String col) {
		List<String> tl = Arrays.asList(tmp.split("\n"));
		for (String s : tl) {
			logbuffer.add("<font color='" + col + "'>" + s + "</font><br />");
		}
	}
}

class CircularFifoBuffer<E> extends AbstractCollection<E> implements Serializable {

	/** Serialization version */
	private static final long serialVersionUID = 5603722811189451017L;

	/** Underlying storage array */
	private transient Object[] elements;

	/** Array index of first (oldest) buffer element */
	private transient int start = 0;

	/**
	 * Index mod maxElements of the array position following the last buffer
	 * element. Buffer elements start at elements[start] and "wrap around"
	 * elements[maxElements-1], ending at elements[decrement(end)]. For example,
	 * elements = {c,a,b}, start=1, end=1 corresponds to the buffer [a,b,c].
	 */
	private transient int end = 0;

	/** Flag to indicate if the buffer is currently full. */
	private transient boolean full = false;

	/** Capacity of the buffer */
	private final int maxElements;

	/**
	 * Constructs a new <code>BoundedFifoBuffer</code> big enough to hold 32
	 * elements.
	 */
	public CircularFifoBuffer() {
		this(32);
	}

	/**
	 * Constructs a new <code>BoundedFifoBuffer</code> big enough to hold the
	 * specified number of elements.
	 * 
	 * @param size
	 *            the maximum number of elements for this fifo
	 * @throws IllegalArgumentException
	 *             if the size is less than 1
	 */
	public CircularFifoBuffer(int size) {
		if (size <= 0) {
			throw new IllegalArgumentException("The size must be greater than 0");
		}
		elements = new Object[size];
		maxElements = elements.length;
	}

	/**
	 * Constructs a new <code>BoundedFifoBuffer</code> big enough to hold all of
	 * the elements in the specified collection. That collection's elements will
	 * also be added to the buffer.
	 * 
	 * @param coll
	 *            the collection whose elements to add, may not be null
	 * @throws NullPointerException
	 *             if the collection is null
	 */
	public CircularFifoBuffer(Collection<E> coll) {
		this(coll.size());
		addAll(coll);
	}

	// -----------------------------------------------------------------------
	/**
	 * Write the buffer out using a custom routine.
	 * 
	 * @param out
	 *            the output stream
	 * @throws IOException
	 */
	private void writeObject(ObjectOutputStream out) throws IOException {
		out.defaultWriteObject();
		out.writeInt(size());
		for (Iterator<E> it = iterator(); it.hasNext();) {
			out.writeObject(it.next());
		}
	}

	/**
	 * Read the buffer in using a custom routine.
	 * 
	 * @param in
	 *            the input stream
	 * @throws IOException
	 * @throws ClassNotFoundException
	 */
	private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		elements = new Object[maxElements];
		int size = in.readInt();
		for (int i = 0; i < size; i++) {
			elements[i] = in.readObject();
		}
		start = 0;
		full = (size == maxElements);
		if (full) {
			end = 0;
		} else {
			end = size;
		}
	}

	// -----------------------------------------------------------------------
	/**
	 * Returns the number of elements stored in the buffer.
	 * 
	 * @return this buffer's size
	 */
	public int size() {
		int size = 0;

		if (end < start) {
			size = maxElements - start + end;
		} else if (end == start) {
			size = (full ? maxElements : 0);
		} else {
			size = end - start;
		}

		return size;
	}

	/**
	 * Returns true if this buffer is empty; false otherwise.
	 * 
	 * @return true if this buffer is empty
	 */
	public boolean isEmpty() {
		return size() == 0;
	}

	/**
	 * Returns true if this collection is full and no new elements can be added.
	 * 
	 * @return <code>true</code> if the collection is full
	 */
	public boolean isFull() {
		return size() == maxElements;
	}

	/**
	 * Gets the maximum size of the collection (the bound).
	 * 
	 * @return the maximum number of elements the collection can hold
	 */
	public int maxSize() {
		return maxElements;
	}

	/**
	 * Clears this buffer.
	 */
	public void clear() {
		full = false;
		start = 0;
		end = 0;
		Arrays.fill(elements, null);
	}

	/**
	 * Adds the given element to this buffer.
	 * 
	 * @param element
	 *            the element to add
	 * @return true, always
	 * @throws NullPointerException
	 *             if the given element is null
	 * @throws BufferOverflowException
	 *             if this buffer is full
	 */
	public boolean add(E element) {
		if (null == element) {
			throw new NullPointerException("Attempted to add null object to buffer");
		}

		if (full) {
			remove();
		}

		elements[end++] = element;

		if (end >= maxElements) {
			end = 0;
		}

		if (end == start) {
			full = true;
		}

		return true;
	}

	/**
	 * Returns the least recently inserted element in this buffer.
	 * 
	 * @return the least recently inserted element
	 * @throws BufferUnderflowException
	 *             if the buffer is empty
	 */
	public Object get() {
		if (isEmpty()) {
			throw new RuntimeException("The buffer is already empty");
		}

		return elements[start];
	}

	/**
	 * Removes the least recently inserted element from this buffer.
	 * 
	 * @return the least recently inserted element
	 * @throws BufferUnderflowException
	 *             if the buffer is empty
	 */
	public Object remove() {
		if (isEmpty()) {
			throw new RuntimeException("The buffer is already empty");
		}

		Object element = elements[start];

		if (null != element) {
			elements[start++] = null;

			if (start >= maxElements) {
				start = 0;
			}

			full = false;
		}

		return element;
	}

	/**
	 * Increments the internal index.
	 * 
	 * @param index
	 *            the index to increment
	 * @return the updated index
	 */
	private int increment(int index) {
		index++;
		if (index >= maxElements) {
			index = 0;
		}
		return index;
	}

	/**
	 * Decrements the internal index.
	 * 
	 * @param index
	 *            the index to decrement
	 * @return the updated index
	 */
	private int decrement(int index) {
		index--;
		if (index < 0) {
			index = maxElements - 1;
		}
		return index;
	}

	/**
	 * Returns an iterator over this buffer's elements.
	 * 
	 * @return an iterator over this buffer's elements
	 */
	public Iterator<E> iterator() {
		return new Iterator<E>() {

			private int index = start;
			private int lastReturnedIndex = -1;
			private boolean isFirst = full;

			public boolean hasNext() {
				return isFirst || (index != end);

			}

			@SuppressWarnings("unchecked")
			public E next() {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}
				isFirst = false;
				lastReturnedIndex = index;
				index = increment(index);
				return (E) elements[lastReturnedIndex];
			}

			public void remove() {
				if (lastReturnedIndex == -1) {
					throw new IllegalStateException();
				}

				// First element can be removed quickly
				if (lastReturnedIndex == start) {
					CircularFifoBuffer.this.remove();
					lastReturnedIndex = -1;
					return;
				}

				int pos = lastReturnedIndex + 1;
				if (start < lastReturnedIndex && pos < end) {
					// shift in one part
					System.arraycopy(elements, pos, elements, lastReturnedIndex, end - pos);
				} else {
					// Other elements require us to shift the subsequent
					// elements
					while (pos != end) {
						if (pos >= maxElements) {
							elements[pos - 1] = elements[0];
							pos = 0;
						} else {
							elements[decrement(pos)] = elements[pos];
							pos = increment(pos);
						}
					}
				}

				lastReturnedIndex = -1;
				end = decrement(end);
				elements[end] = null;
				full = false;
				index = decrement(index);
			}

		};
	}

}