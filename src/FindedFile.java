import java.nio.file.Path;

public class FindedFile {
	Path pathToFile; // путь к файлу
	long offset; // смещение с начала файла

	FindedFile(Path path, long off){
		pathToFile = path;
		offset = off;
	}
}
