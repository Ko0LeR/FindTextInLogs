import java.nio.file.Path;

public class FindedFile {
	Path pathToFile; // ���� � �����
	long offset; // �������� � ������ �����

	FindedFile(Path path, long off){
		pathToFile = path;
		offset = off;
	}
}
