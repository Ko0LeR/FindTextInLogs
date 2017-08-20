import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.concurrent.*;

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
	/** какой файл считаем большим */
	private final int maxFileSize = 50_000_000;
	/** пул потоков поиска текста в файле */
	private ExecutorService threadPool, largeFilesPool; 
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
				// в пул добавляем новую задачу поиска текста в файле
				if(file.toFile().length() < maxFileSize) 
					threadPool.execute(() -> { 
						checkFileForNeedText(file);
					});
				else 
					largeFilesPool.execute(() -> { // в пул добавляем новую задачу поиска текста в файле
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
		this.textToFind = textToFind;
		queue.clear();
		totalFiles = 0;
		startedTime = System.nanoTime(); 
		threadPool = Executors.newCachedThreadPool();
		largeFilesPool = Executors.newSingleThreadExecutor(); // используем отдельный однопоточный пул
															// для файлов > ~50 Мб
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
		filesInProgressCount++;
		long posInFile = -1;
		// если файл найден и поток не прерван, добавляем файл в очередь
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
	 * Функция поиска текста в файле
	 * @param path путь к файлу
	 * @param inputStr искомая строка
	 * @return позиция искомой строки в файле
	 */
	private long findText(String path, String inputStr) {
		byte[] inputStrByte = inputStr.getBytes(); // переводим строку в байты
		try(RandomAccessFile file = new RandomAccessFile(path, "r")){ // открываем файл для чтения
			if(file.getChannel().size() <= Integer.MAX_VALUE) // если размер файла меньше Integer.MAX_VALUE
				return findInFile(inputStrByte, file, 0, file.getChannel().size()); // ищем текст в файле и возвращаем результат
			else {
				long filePos = -1;
				for(long i = 0; i < file.getChannel().size(); i += Integer.MAX_VALUE) { // делим файл на части и обрабатываем каждую
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
	 * Функция поиска текста в файле
	 * @param inputStrByte Искомая строка в байтах
	 * @param file Файл, откуда читаем
	 * @param from С какой позиции надо читать
	 * @param size Сколько байт надо читать
	 * @return Позиция в файле или -1, если текст не найден
	 */
	private long findInFile(byte[] inputStrByte, RandomAccessFile file, long from, long size) throws IOException {
		MappedByteBuffer buffer = file.getChannel().map(MapMode.READ_ONLY, from, size); // открываем регион файла только для чтения
		byte[] buf = new byte[inputStrByte.length]; // сюда будем записывать строку из файла
		buf[0] = inputStrByte[0]; // первый символ строки неизменный
		while(!Thread.currentThread().isInterrupted() && buffer.hasRemaining() && buffer.remaining() >= buf.length) {
			if(buffer.get() == inputStrByte[0]) { // если считанный байт совпал с началом строки
				buffer.mark(); // запоминаем текущую позицию
				buffer.get(buf, 1, buf.length - 1); // считываем остаток строки
				buffer.reset();	// возвращаемся к запомненной позиции
				if(Arrays.equals(buf, inputStrByte)) // если строки совпали
					return buffer.position(); 
			}						
		}
		return -1;
	}
	
	/** Функция остановки поиска */
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