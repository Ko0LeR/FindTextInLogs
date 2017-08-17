import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

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
		filesInProgressCount++;
		// ���� ���� ������ � ����� �� �������, ��������� ���� � �������
		if(findText(path.toString(), textToFind) && !Thread.currentThread().isInterrupted())
			addToQueue(path);
		filesInProgressCount--;
		filesDoneCount++;
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) { }
		if(totalFiles == filesDoneCount && !searching) 
			processing = false;
	}
	
	/**
	 * ������� ������ ������ � �����
	 * @param path ���� � �����
	 * @param inputStr �����, ������� ���������� �����
	 * @return ������ ���� ��� ���
	 */
	private boolean findText(String path, String inputStr) {
		try(Stream<String> stream = Files.lines(Paths.get(path))) { // ������ stream
			return stream.anyMatch(match -> match.contains(inputStr)); // ���� ������ ����������
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
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