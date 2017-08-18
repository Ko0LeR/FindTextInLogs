import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Класс для осуществления поиска файлов с нужным расширением
 */
public class FindFiles {
	/** находятся ли в файлы в обработке */
	public volatile boolean processing = false;
	/** происходит ли сейчас обход файловой системы */
	public volatile boolean searching = false; 
	/** очередь для хранения путей к найденным файлам */
	public volatile ConcurrentLinkedQueue<FindedFile> queue = new ConcurrentLinkedQueue<FindedFile>();
	/** в скольки файлах ищется нужный текст */
	public volatile int filesInProgressCount = 0; 
	/** сколько файлов обработано */
	public volatile int	filesDoneCount = 0;
	/** сколько всего файлов нашли с нужным расширением */
	public volatile int	totalFiles = 0; 
	/** начальное время работы */
	public volatile long startedTime = System.nanoTime(); 
	/** сколько байт читаем за 1 раз из файла */
	public static volatile int maxBts = 20_000_000;
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
				threadPool.execute(() -> { // в пул добавляем новую задачу поиска текста в файле
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
		threadPool = Executors.newCachedThreadPool();
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
	public synchronized void addToQueue(Path path, long offset) {
		queue.add(new FindedFile(path, offset));
	}
	
	/**
	 * Функция извлечения пути к файлу из очереди
	 * @return Путь к файлу
	 */
	public synchronized FindedFile getFromQueue() {
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
		synchronized(FindFiles.class) {
			filesInProgressCount++;
		}
		long posInFile = -1;
		// если файл найден и поток не прерван, добавляем файл в очередь
		if((posInFile = findText(path.toString(), textToFind)) != -1 && !Thread.currentThread().isInterrupted())
			addToQueue(path, posInFile);
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
	 * Функция поиска текста в файле
	 * @param path путь к файлу
	 * @param inputStr искомая строка
	 * @return позиция искомой строки в файле
	 */
	private long findText(String path, String inputStr) {
		byte[] inputStrByte = inputStr.getBytes(); // переводим строку в байты
		long time = System.nanoTime();
		try(RandomAccessFile file = new RandomAccessFile(path, "r")){ // открываем файл для чтения
			if(file.getChannel().size() <= Integer.MAX_VALUE) {
				return findInFile(inputStrByte, file, 0, file.getChannel().size());

			}else {
				long remain = file.getChannel().size();
				for(long i = 0; i < file.getChannel().size(); i += Integer.MAX_VALUE) {
					remain -= Integer.MAX_VALUE;
					if(remain >= Integer.MAX_VALUE)
						findInFile(inputStrByte, file, i, Integer.MAX_VALUE);
					else
						findInFile(inputStrByte, file, i, remain);
				}
				file.getChannel().close();
				file.close();
			}
		}catch(IOException e) {
			return -1;
		}
		
		System.out.println("TIME: " + (System.nanoTime() - time)/1000000000.0 + " at " + path);
		return -1;
 	}	
	
	long findInFile(byte[] inputStrByte, RandomAccessFile file, long from, long size) throws IOException {
		MappedByteBuffer buffer = file.getChannel().map(MapMode.READ_ONLY, from, size);
		byte[] buf = new byte[inputStrByte.length];
		buf[0] = inputStrByte[0];
		while(!Thread.currentThread().isInterrupted() && buffer.hasRemaining() && buffer.remaining() >= buf.length) {
			if(buffer.get() == inputStrByte[0]) {
				buffer.mark();
				buffer.get(buf, 1, buf.length - 1);
				buffer.reset();
				if(Arrays.equals(buf, inputStrByte)) 
					return buffer.position();
			}						
		}
		return -1;
	}
	
	/** Функция остановки поиска */
	public void stopSearch() {
		if(threadPool != null) {
			threadPool.shutdownNow();
			searching = false;
			processing = false;
			filesInProgressCount = 0;
		}
	}
}