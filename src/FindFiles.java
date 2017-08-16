import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Класс для осуществления поиска файлов с нужным расширением
 */
public class FindFiles {
	/** находятся ли в файлы в обработке */
	public volatile boolean processing = false;
	/** происходит ли сейчас обход файловой системы */
	public volatile boolean searching = false; 
	/** очередь для хранения путей к найденным файлам */
	public volatile ConcurrentLinkedQueue<Path> queue = new ConcurrentLinkedQueue<Path>();
	/** в скольки файлах ищется нужный текст */
	public volatile int filesInProgressCount = 0; 
	/** сколько файлов обработано */
	public volatile int	filesDoneCount = 0;
	/** сколько всего файлов нашли с нужным расширением */
	public volatile int	totalFiles = 0; 
	/** начальное время работы */
	public volatile long startedTime = System.nanoTime(); 
	/** пул потоков поиска текста в файле */
	private ExecutorService threadPool; 
	/** текст, который необходимо найти */
	private String textToFind;	
	/** instance для реализации синглтона */
	private static volatile FindFiles instance;
	/** класс для обхода файловой системы*/
	class TreeWalker implements FileVisitor<Path>{
		/** нужное расширение файлов */
		private String extension;
		
		public TreeWalker(String ext) {
			extension = ext;
		}
		
		/** функция, определяющая, что делать при посещении файла */
		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attr) {
			if(file.toString().endsWith(extension)) { // если файл имеет нужное расширение
				totalFiles++; // увеличиваем количество найденных файлов с таким расширением
				threadPool.submit(() -> { // в пул добавляем новую задачу поиска текста в файле
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
	
	/** Закрытый конструктор класса для реализации синглтона*/
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
	 * Функция начала поиска файлов
	 * @param textToFind Текст, который необходимо найти
	 * @param pathToDir Путь к директории, в которой будем искать
	 * @param extension Расширение файла
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
		// создаём пул потоков по количеству ядер процессора
		threadPool = Executors.newFixedThreadPool (Runtime.getRuntime().availableProcessors());
		try {			
			// запускаем обход дерева
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
	 * Функция добавления пути в очередь
	 * @param path Путь к файлу
	 */
	public synchronized void addToQueue(Path path) {
		queue.add(path);
	}
	
	/**
	 * Функция извлечения пути к файлу из очереди
	 * @return Путь к файлу
	 */
	public synchronized Path getFromQueue() {
		return queue.poll();
	}
	
	/**
	 * Проверка очереди на пустоту
	 * @return пуста ли очередь
	 */
	public boolean isQueueEmpty() {
		return queue.isEmpty();
	}
	
	/**
	 * Функция проверки файла на содержание в нём нужного текста
	 * @param path Путь к файлу
	 */
	private void checkFileForNeedText(Path path) {
		filesInProgressCount++;
		// если файл найден и поток не прерван, добавляем файл в очередь
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
	 * Фукнция поиска текста в файле
	 * @param path Путь к файлу
	 * @param inputStr Текст, который необходимо найти
	 * @return Найден файл или нет
	 */
	private boolean findText(String path, String inputStr) {
		try(Stream<String> stream = Files.lines(Paths.get(path))) { // создаём stream
			return stream.anyMatch(match -> match.contains(inputStr)); // ищем первое совпадение
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	/** Функция остановки поиска */
	public void stopSearch() {
		if(threadPool != null) {
			searching = false;
			processing = false;
			filesInProgressCount = 0;
			threadPool.shutdownNow();
		}
	}
}