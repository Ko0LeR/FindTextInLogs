import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ����� ��� ������������� ������ ������ � ������ �����������
 */
public class FindFiles {
	/** ��������� �� � ����� � ��������� */
	public volatile boolean processing = false;
	/** ���������� �� ������ ����� �������� ������� */
	public volatile boolean searching = false; 
	/** ������� ��� �������� ����� � ��������� ������ */
	public volatile ConcurrentLinkedQueue<Path> queue = new ConcurrentLinkedQueue<Path>();
	/** � ������� ������ ������ ������ ����� */
	public volatile int filesInProgressCount = 0; 
	/** ������� ������ ���������� */
	public volatile int	filesDoneCount = 0;
	/** ������� ����� ������ ����� � ������ ����������� */
	public volatile int	totalFiles = 0; 
	/** ��������� ����� ������ */
	public volatile long startedTime = System.nanoTime(); 
	/** ��� ������� ������ ������ � ����� */
	private ExecutorService threadPool; 
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
				threadPool.submit(() -> { // � ��� ��������� ����� ������ ������ ������ � �����
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
		startedTime = System.nanoTime();
		this.textToFind = textToFind;
		queue.clear();
		totalFiles = 0;
		// ������ ��� ������� �� ���������� ���� ����������
		threadPool = Executors.newFixedThreadPool (Runtime.getRuntime().availableProcessors());
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
	public synchronized void addToQueue(Path path) {
		queue.add(path);
	}
	
	/**
	 * ������� ���������� ���� � ����� �� �������
	 * @return ���� � �����
	 */
	public synchronized Path getFromQueue() {
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
		synchronized(FindFiles.class) {
			filesInProgressCount++;
		}
		// ���� ���� ������ � ����� �� �������, ��������� ���� � �������
		if(findText(path.toString(), textToFind) != -1 && !Thread.currentThread().isInterrupted())
			addToQueue(path);
		synchronized(FindFiles.class) {
			filesInProgressCount--;
			filesDoneCount++;
		}
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) { }
		if(totalFiles == filesDoneCount && !searching) 
			processing = false;
	}
	
	/**
	 * ������� ������ ������ � �����
	 * @param path ���� � �����
	 * @param inputStr ������� ������
	 * @return ������� ������� ������ � �����
	 */
	private long findText(String path, String inputStr) {
		int maxBts = 5_000_000; // ������� ���� ������ �� 1 ���
		byte[] byteStrFromFile = new byte[5_000_000]; // ���� ����������� ����� �� �����
		byte[] inputStrByte = inputStr.getBytes(); // ��������� ������ � �����
		long offset = 0; // �������� �� ������ �����
		int resultPos = -1; // ������������ ��������
		long fileLength = new File(path).length(); // ����� ��������� �����
		try(RandomAccessFile file = new RandomAccessFile(path, "r")){ // ��������� ���� ��� ������
			while(file.read(byteStrFromFile) != -1) { //��������� �� �����, ���� �����
				if((resultPos = findSubArray(byteStrFromFile, inputStrByte)) != -1) { // ���� ��������� ���������� - �������
					if(offset == 0)
						return resultPos;
					return resultPos * offset;
				}
				offset += maxBts / 2;  // ����������� ��������
				file.seek(offset); // ������� ������� � �����
				Arrays.fill(byteStrFromFile, (byte)0); // ��������� ������ ������
			}
			if(new File(path).length() > maxBts) { // ���� ���� ������ 
				Arrays.fill(byteStrFromFile, (byte)0); // ��������� ������ ������
				file.seek(fileLength - maxBts); // ��������� � ����� ����� �� ������ �������
				file.read(byteStrFromFile); 
				if((resultPos = findSubArray(byteStrFromFile, inputStrByte)) != -1) { // ���� ��������� ���������� - �������
					if(offset == 0)
						return resultPos;
					return resultPos * offset;
				}
			}
		}catch(IOException e) {
			return resultPos;
		}
		return resultPos;
	}
	
	/**
	 * ������� ������ ���������� � �������
	 * @param array ������, � ������� ����
	 * @param subarray ��� ����
	 * @return ������� � ������� ��� -1, ���� ���������� �� ����
	 */
	private int findSubArray(byte[] array, byte[] subarray) {
		Queue<Integer> rememberedPos = new LinkedList <Integer>(); // ����� ����� ���������� ������� � array �� ��������� subarray[0]
		int i = 0, k = 0;
		for(; i < array.length && k < subarray.length; ) { // ���� ����� ���� �� ��������
			if(array[i] == subarray[0] && k != 0) // ���� �� ����� ����, � �������� ���������� subarray � �� ������ �� � ������ subarray 
				rememberedPos.add(i); // ���������� �������
			if(array[i] == subarray[k]) { // ���� ����� �����
				i++; //���������
				k++;
			}
			else {
				k = 0;
				if(!rememberedPos.isEmpty()) { // ���� � ��� ���� ����������� �������
					i = rememberedPos.poll(); // ��������� � ��
				}else
					i++;
			}
		}
		if(k == subarray.length) 
			return i;
		return -1;
	}
	
	
	
	/** ������� ��������� ������ */
	public void stopSearch() {
		if(threadPool != null) {
			searching = false;
			processing = false;
			filesInProgressCount = 0;
			threadPool.shutdownNow();
		}
	}
}