import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.concurrent.*;

/**
 * ����� ��� ������������� ������ ������ � ������ �����������
 */
public class FindFiles {
	/** ��������� �� � ����� � ��������� */
	public volatile boolean processing = false;
	/** ���������� �� ������ ����� �������� ������� */
	public volatile boolean searching = false; 
	/** ������� ��� �������� ����� � ��������� ������ */
	public volatile ConcurrentLinkedQueue<FindedFile> queue = new ConcurrentLinkedQueue<FindedFile>();
	/** � ������� ������ ������ ������ ����� */
	public volatile int filesInProgressCount = 0; 
	/** ������� ������ ���������� */
	public volatile int	filesDoneCount = 0;
	/** ������� ����� ������ ����� � ������ ����������� */
	public volatile int	totalFiles = 0; 
	/** ��������� ����� ������ */
	public volatile long startedTime = System.nanoTime(); 
	/** ����� ���� ������� ������� */
	private final int maxFileSize = 50_000_000;
	/** ��� ������� ������ ������ � ����� */
	private ExecutorService threadPool, largeFilesPool; 
	/** �����, ������� ���������� ����� */
	private String textToFind;	
	/** instance ��� ���������� ��������� */
	private static volatile FindFiles instance;
	
	/** ����� ��� ������ �������� �������*/
	class TreeWalker implements FileVisitor<Path>{
		/** ������ ���������� ������ */
		private String extension;
		
		public TreeWalker(String ext) {
			extension = ext;
		}
		
		/** �������, ������������, ��� ������ ��� ��������� ����� */
		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attr) {
			if(file.toString().endsWith(extension)) { // ���� ���� ����� ������ ����������
				totalFiles++; // ����������� ���������� ��������� ������ � ����� �����������
				// � ��� ��������� ����� ������ ������ ������ � �����
				if(file.toFile().length() < maxFileSize) 
					threadPool.execute(() -> { 
						checkFileForNeedText(file);
					});
				else 
					largeFilesPool.execute(() -> { // � ��� ��������� ����� ������ ������ ������ � �����
						checkFileForNeedText(file);
					});
			}			
			if(Thread.currentThread().isInterrupted()) 
				return FileVisitResult.TERMINATE;
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult postVisitDirectory(Path path, IOException arg1) throws IOException {
			if(Thread.currentThread().isInterrupted())
				return FileVisitResult.TERMINATE;
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes arg1) throws IOException {
			if(Thread.currentThread().isInterrupted())
				return FileVisitResult.TERMINATE;
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path path, IOException arg1) throws IOException {
			if(Thread.currentThread().isInterrupted())
				return FileVisitResult.TERMINATE;
			return FileVisitResult.CONTINUE;
		}
	}
	
	/** �������� ����������� ������ ��� ���������� ���������*/
	private FindFiles() {}
	
	public static synchronized FindFiles getInstance() {
		if(instance == null) {
			synchronized(FindFiles.class) {
				if(instance == null)
					instance = new FindFiles();
			}
		}
		return instance;
	}
	
	/**
	 * ������� ������ ������ ������
	 * @param textToFind �����, ������� ���������� �����
	 * @param pathToDir ���� � ����������, � ������� ����� ������
	 * @param extension ���������� �����
	 */
	public void findFilesInDirectory(String textToFind, String pathToDir, String extension) {
		processing = true;
		searching = true;
		Path path = Paths.get(pathToDir);
		filesInProgressCount = 0;
		filesDoneCount = 0;
		this.textToFind = textToFind;
		queue.clear();
		totalFiles = 0;
		startedTime = System.nanoTime(); 
		threadPool = Executors.newCachedThreadPool();
		largeFilesPool = Executors.newSingleThreadExecutor(); // ���������� ��������� ������������ ���
															// ��� ������ > ~50 ��
		try {			
			// ��������� ����� ������
			Files.walkFileTree(path, new TreeWalker(extension));
			searching = false;
			if(totalFiles == filesDoneCount) 
				processing = false;
		}
		catch(IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * ������� ���������� ���� � �������
	 * @param path ���� � �����
	 */
	public synchronized void addToQueue(Path path, long offset) {
		queue.add(new FindedFile(path, offset));
	}
	
	/**
	 * ������� ���������� ���� � ����� �� �������
	 * @return ���� � �����
	 */
	public synchronized FindedFile getFromQueue() {
		return queue.poll();
	}
	
	/**
	 * �������� ������� �� �������
	 * @return ����� �� �������
	 */
	public boolean isQueueEmpty() {
		return queue.isEmpty();
	}
	
	/**
	 * ������� �������� ����� �� ���������� � �� ������� ������
	 * @param path ���� � �����
	 */
	private void checkFileForNeedText(Path path) {
		filesInProgressCount++;
		long posInFile = -1;
		// ���� ���� ������ � ����� �� �������, ��������� ���� � �������
		if((posInFile = findText(path.toString(), textToFind)) != -1 && !Thread.currentThread().isInterrupted())
			addToQueue(path, posInFile);
		if(filesInProgressCount - 1 >= 0)
			filesInProgressCount--;
		if(Thread.currentThread().isInterrupted())
			return;
		filesDoneCount++;
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) { }
		if(totalFiles == filesDoneCount && !searching) 
			stopSearch();
	}
	
	/**
	 * ������� ������ ������ � �����
	 * @param path ���� � �����
	 * @param inputStr ������� ������
	 * @return ������� ������� ������ � �����
	 */
	private long findText(String path, String inputStr) {
		byte[] inputStrByte = inputStr.getBytes(); // ��������� ������ � �����
		try(RandomAccessFile file = new RandomAccessFile(path, "r")){ // ��������� ���� ��� ������
			if(file.getChannel().size() <= Integer.MAX_VALUE) // ���� ������ ����� ������ Integer.MAX_VALUE
				return findInFile(inputStrByte, file, 0, file.getChannel().size()); // ���� ����� � ����� � ���������� ���������
			else {
				long filePos = -1;
				for(long i = 0; i < file.getChannel().size(); i += Integer.MAX_VALUE) { // ����� ���� �� ����� � ������������ ������
					if(i + Integer.MAX_VALUE <= file.getChannel().size())
						filePos = findInFile(inputStrByte, file, i, Integer.MAX_VALUE);
					else
						filePos = findInFile(inputStrByte, file, i, file.getChannel().size() - i);
					if(filePos != -1)
						return filePos;
				}
			}
			file.getChannel().close();
		}catch(IOException e) {
			return -1;
		}
		return -1;
 	}	

	/**
	 * ������� ������ ������ � �����
	 * @param inputStrByte ������� ������ � ������
	 * @param file ����, ������ ������
	 * @param from � ����� ������� ���� ������
	 * @param size ������� ���� ���� ������
	 * @return ������� � ����� ��� -1, ���� ����� �� ������
	 */
	private long findInFile(byte[] inputStrByte, RandomAccessFile file, long from, long size) throws IOException {
		MappedByteBuffer buffer = file.getChannel().map(MapMode.READ_ONLY, from, size); // ��������� ������ ����� ������ ��� ������
		byte[] buf = new byte[inputStrByte.length]; // ���� ����� ���������� ������ �� �����
		buf[0] = inputStrByte[0]; // ������ ������ ������ ����������
		while(!Thread.currentThread().isInterrupted() && buffer.hasRemaining() && buffer.remaining() >= buf.length) {
			if(buffer.get() == inputStrByte[0]) { // ���� ��������� ���� ������ � ������� ������
				buffer.mark(); // ���������� ������� �������
				buffer.get(buf, 1, buf.length - 1); // ��������� ������� ������
				buffer.reset();	// ������������ � ����������� �������
				if(Arrays.equals(buf, inputStrByte)) // ���� ������ �������
					return buffer.position(); 
			}						
		}
		return -1;
	}
	
	/** ������� ��������� ������ */
	public void stopSearch() {
		if(threadPool != null) 
			threadPool.shutdownNow();
		if(largeFilesPool != null)
			largeFilesPool.shutdownNow();
		searching = false;
		processing = false;
		filesInProgressCount = 0;
	}
}