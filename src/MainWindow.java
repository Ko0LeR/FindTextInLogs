import org.eclipse.swt.widgets.*;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

public class MainWindow {
	private final String PAGE_TEXT = "Page",
						 TOTALPAGES_TEXT = "TotalPages",
						 PATH_TEXT = "Path",
						 RUNNINGTHREAD_NAME = "RunningThread",
						 OFFSET_TEXT = "Offset",
						 STARTSEARCHTEXT = "Начать поиск", 
						 STOPSEARCHTEXT = "Остановить поиск";
	/** Выбранный путь для поиска */
	private String selectedPath;
	/** Название окна */
	private String windowTitle = "Text Finder";
	/** Дерево файловой системы */
	private Tree fileSystemTree;
	/** Хранилище вкладок с текстом файла */
	private CTabFolder tabFolder;
	/** Кнопка начала поиска */
	private Button searchButton;
	/** Тулбар */
	private Label toolBarText;
	/** Сюда выводим, сколько страниц осталось */
	private Label buttonsStatusText;
	/** здесь запускаем:
			- поиск файлов в директории
			- обновления статусбара
			- обновление дерева файловой системы  */
	private ExecutorService threadPool;
	
	private final int maxElems = 20_000_000, // количество байт, читаемых за 1 раз
			maxCapacity = 100_000_000; // максимальное количество байт, которое будем выводить на одну страницу
	
	private volatile int maxCharsAtOneMoment = 400_000_000;
	private volatile int curChars = 0;
	
	/** Конструктор главного окна приложения */
	public MainWindow() {		
		final Display display = Display.getDefault();	
		final Shell shell = new Shell();
		shell.setSize(800, 600); // устанавливаем начальный размер окна
		shell.setText(windowTitle); // устанавливаем название окна
		shell.setMinimumSize(400, 300); // выставляем минимальный размер окна
		shell.setLayout(new GridLayout());		
		
		// обрабатываем завершение работы shell
		shell.addDisposeListener(new DisposeListener() {
			@Override
			public void widgetDisposed(DisposeEvent e) {
				FindFiles.getInstance().stopSearch();
				if(threadPool != null)
					threadPool.shutdownNow();
			}
		});
		
		createBody(shell); // создаём центральную часть окна с Tree и контейнером для вкладок
		createStatusBar(shell); // создаём информационную строку
		
		shell.open();
		while (!shell.isDisposed()) 
			if (!display.isDisposed() && !display.readAndDispatch()) 
				display.sleep();
		
		if(display != null && !display.isDisposed()) 
			display.dispose();
	}
	
	/**
	 * Функция создания "верхушки" окна - поля для ввода текста, расширения, кнопки поиска и т.д.
	 */
	private void createTop(Shell shell, Composite parent) {
	        Composite header = new Composite (parent, SWT.NONE);
	        
	        header.setLayout(new GridLayout(4, false));
	        header.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
	        
	        new Label(header, SWT.NONE).setText("Текст");
	        
	        Text inputText = new Text(header, SWT.BORDER | SWT.SINGLE); // поле для ввода текста
	        GridData gridData = new GridData(GridData.FILL, GridData.CENTER, true, false);
	        gridData.horizontalSpan = 2;
	        inputText.setLayoutData(gridData);

	        Label label1 = new Label(header, SWT.HORIZONTAL); // кнопка выбора новой директории
	        label1.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, false));
	        label1.setImage(new Image(Display.getDefault(),".\\img\\folder.png"));
	        label1.setToolTipText("Выбор новой директории");
	        label1.addMouseListener(new MouseAdapter() { // при нажатии кнопкой мыши - 
	        	@Override								// выбираем новую директорию
	        	public void mouseDown(MouseEvent e) {
	        		openFindPathWindow(shell); 
	        	}
	        });
	        new Label(header, SWT.NONE).setText("Расширение: *.");
	        
	        Text inputExtension = new Text(header, SWT.BORDER | SWT.SINGLE); // поле для ввода расширения
	        inputExtension.setText("log"); // изначально ищем файлы *.log
	        inputExtension.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
	        
	        searchButton = new Button(header, SWT.NONE); // кнопка начала поиска
	        searchButton.setText(STARTSEARCHTEXT);
	        searchButton.addSelectionListener(new SelectionAdapter() {
	        	@Override
	        	public void widgetSelected(SelectionEvent e) { // при нажатии на кнопку
		        	if(searchButton.getText().equals(STARTSEARCHTEXT)) { // если мы не в процессе поиска
		        		if(selectedPath == null) { // если путь не выбран
		        			openFindPathWindow(shell); // открываем окно выбора пути
		        			if(selectedPath == null) { // если путь до сих пор не выбран
		        				showTooltip(label1, "Необходимо выбрать путь!"); // выходим и выводим tooltip
		        				return;
		        			}
		        		}
		        		startFindFiles(inputText, inputExtension); // начинаем искать файлы
	        		} 
		        	else {
		        		changeButton(false); // меняем надпись на кнопку
		        		FindFiles.getInstance().stopSearch(); // завершаем поиск
		        		if(threadPool != null) // завершаем запущенные потоки
		        			threadPool.shutdownNow();
		        		updateStatusBar(); // обновляем статусбар
		        	}	        		
	        	}
	        });	        
	}
	
	/** Функция изменения надписи на кнопке */
	private void changeButton(boolean searchStarted) {
		if(searchButton != null && !searchButton.isDisposed()) {
			if(searchStarted) 
				searchButton.setText(STOPSEARCHTEXT);
			else 
				searchButton.setText(STARTSEARCHTEXT);
			searchButton.pack(); // изменяем размер кнопки под новый текст
		}
	}
	
	/**
	 * Функция создания статусбара
	 */
	private void createStatusBar(Shell shell) {
		toolBarText = new Label(shell, SWT.NONE);
		toolBarText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
		toolBarText.setText(" ");
	}

	/**
	 * Фукнция открытия файла в новой вкладке
	 * @param pathToFile
	 */
	private void openFileInNewTab(String pathToFile, long offset) {
		for(int i = 0; i < tabFolder.getItemCount(); i++) // если такой файл уже открыт, то второй раз не откроем
			if(tabFolder.getItem(i).getData(PATH_TEXT).toString().equals(pathToFile))
				return;
		CTabItem newTab = new CTabItem(tabFolder, SWT.CLOSE); // создаём новую вкладку
		
		Composite compositePage = new Composite(tabFolder, SWT.NONE);
		compositePage.setLayout(new GridLayout());
		
		Composite textFolder = new Composite(compositePage, SWT.NONE);
		textFolder.setLayout(new GridLayout());
		textFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true)); // растягиваем его

		newTab.setData(PATH_TEXT, pathToFile); // запоминаем путь к файлу
		newTab.setData(OFFSET_TEXT, offset);
		newTab.setText(pathToFile.substring(pathToFile.lastIndexOf("\\") + 1, pathToFile.length())); // выводим название
		newTab.setImage(new Image(Display.getDefault(), ".\\img\\tabLoading.gif"));
		newTab.addDisposeListener(new DisposeListener() { 
			@Override
			public void widgetDisposed(DisposeEvent e) { // если виджет уничтожен
				Thread fileOpener = ((Thread) newTab.getData(RUNNINGTHREAD_NAME)); // получаем поток вывода файла
				if(fileOpener != null && !fileOpener.isInterrupted()) { // если он работает
					fileOpener.interrupt(); // прекращаем его работу
				}
				StyledText text = ((StyledText) textFolder.getChildren()[0]);
				curChars -= text.getCharCount();
				text.setText(" ");
				text.dispose(); // удаляем место для текста
				textFolder.dispose();

				compositePage.dispose();
				newTab.dispose();
			}
		});
		tabFolder.setSelection(newTab); // открываем новую вкладку
		
		// Если больше 1 странцы в файле
		if((int)((new File(pathToFile).length()) / maxCapacity) > 0) 
			createPagesButtons(compositePage, textFolder, true); // создаём кнопки перехода на новую странцу
		else
			createPagesButtons(compositePage, textFolder, false);
		newTab.setControl(compositePage);
		
		readFromFileToTextBrowser(textFolder, pathToFile, newTab); // выводим файл
	}
	
	private StyledText createNewTextBrowser(Composite textFolder) {
		if(textFolder.getChildren().length > 0) {
			StyledText oldText = (StyledText)textFolder.getChildren()[0];
			if(oldText != null && !oldText.isDisposed()) {
				if(oldText.getText().length() > 0) {
					curChars -= oldText.getCharCount();
					oldText.dispose();
				}
				else
					return oldText;
			}
		}
		StyledText textBrowser = new StyledText(textFolder, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY); // создаём место для текста в новой вкладке
		
		textBrowser.setLayout(new GridLayout());
		textBrowser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true)); // растягиваем его
		textBrowser.setText("");
		textBrowser.setSize(textFolder.getSize());
		textBrowser.addKeyListener(new KeyListener() {
			@Override
			public void keyPressed(KeyEvent e) { // ожидаем нажатия клавиш
				if(e.character == 0x01)  // сочетание Ctrl+A
					textBrowser.selectAll(); // выбираем весь текст
				else
				if(e.keyCode == SWT.HOME) // кнопка Home
					textBrowser.setSelection(0); // переходим в начало
				else
				if(e.keyCode == SWT.END) // кнопка End
					textBrowser.setSelection(textBrowser.getCharCount()); // переходим в конец
			}
			@Override
			public void keyReleased(KeyEvent e) {}
		});
		return textBrowser;
	}
	
	/** Функция создания кнопок перелистывания страниц под текстом */
	private void createPagesButtons(Composite parent, Composite textFolder, boolean needToMakeButtons) {
        Composite buttonsComposite = new Composite(parent, SWT.NONE);
        buttonsComposite.setLayout(new GridLayout(4, false));
        GridData gridData = new GridData(SWT.FILL, SWT.FILL, true, false);
        buttonsComposite.setLayoutData(gridData);
        final Label buttonBack; // кнопка "назад"
        final Label buttonForward; // кнопка "вперёд"
        if(needToMakeButtons) {
	        buttonBack = new Label(buttonsComposite, SWT.NONE); // кнопка "назад"
	        buttonForward = new Label(buttonsComposite, SWT.NONE); // кнопка "вперёд"
	        buttonsStatusText = new Label(buttonsComposite, SWT.NONE); // label вывода страниц
	        buttonsStatusText.setText("Страница:");
	        
	        buttonBack.setImage(new Image(Display.getDefault(), ".\\img\\back.png"));
	        buttonBack.setVisible(false); // скрываем кнопку назад (мы же точно будем на 1 странице)
	        buttonBack.setToolTipText("Перейти на предыдущую страницу");
	        buttonBack.addMouseListener(new MouseAdapter() {
	        	@Override
	        	public void mouseDown(MouseEvent e) { // при нажатии на кнопку "на страницу назад"
	        		CTabItem selectedTab = tabFolder.getSelection(); 
	        		if(selectedTab != null) {
	        			// переходим назад на одну страницу
	        			openPage(selectedTab, (int)selectedTab.getData(PAGE_TEXT) - 1, new Label[] {buttonBack, buttonForward});
	        			readFromFileToTextBrowser(textFolder, (String)selectedTab.getData(PATH_TEXT), selectedTab); // выводим новую страницу из файла
	        		}
	        	}
	        });        
	        
	        buttonForward.setImage(new Image(Display.getDefault(), ".\\img\\forward.png"));
	        buttonForward.setToolTipText("Перейти на следующую страницу");
	        buttonForward.addMouseListener(new MouseAdapter() {
	        	@Override
	        	public void mouseDown(MouseEvent e) { // при нажатии на кнопку "на страницу вперёд"
	        		CTabItem selectedTab = tabFolder.getSelection();
	        		if(selectedTab != null) {
	        			// переходим вперёд на одну странцу
	        			openPage(selectedTab, (int)selectedTab.getData(PAGE_TEXT) + 1, new Label[] {buttonBack, buttonForward});
	        			readFromFileToTextBrowser(textFolder, (String)selectedTab.getData(PATH_TEXT), selectedTab); // выводим новую страницу из файла
	        		}
	        	}
	        });
        } 
        else {
        	buttonBack = null;
            buttonForward = null;
        }
        Label buttonGoToText = new Label(buttonsComposite, SWT.NONE); // кнопка "Перейти к тексту"
        buttonGoToText.setImage(new Image(Display.getDefault(), ".\\img\\goToString.png"));
        buttonGoToText.setToolTipText("Перейти к найденному тексту");
        buttonGoToText.addMouseListener(new MouseAdapter() {
        	@Override
        	public void mouseDown(MouseEvent e) { // при нажатии на кнопку "Перейти к тексту"
        		CTabItem selectedTab = tabFolder.getSelection();
        		long offset = (long)selectedTab.getData(OFFSET_TEXT);
        		int page = (int)selectedTab.getData(PAGE_TEXT);
        		if(page != (int)(offset / maxCapacity) + 1) {
        			openPage(selectedTab, (int)(offset / maxCapacity) + 1, new Label[] {buttonBack, buttonForward});
        			readFromFileToTextBrowser(textFolder, (String)selectedTab.getData(PATH_TEXT), selectedTab); // выводим новую страницу из файла
        		}
        		StyledText text = ((StyledText) textFolder.getChildren()[0]);
        		text.setCaretOffset((int) (offset - (int)(offset / maxCapacity) * maxCapacity) - 1);
        		text.setFocus();
        		text.showSelection();
        	}
        });
	}
	
	
	/**
	 * Функция открытия новой страницы файла
	 * @param textBrowser Место для текста
	 * @param path Путь к файлу
	 * @param tab Вкладка, в которую выводим
	 * @param next Следующая странца - true; Предыдущая - false
	 * @param buttons 
	 */
	private void openPage(CTabItem tab, int page, Label[] buttons) {
		int totalPages = (int)tab.getData(TOTALPAGES_TEXT); // получаем полное количество страниц
		if(page > totalPages || page < 1)
			return;
		tab.setData(PAGE_TEXT, page); // сохраняем текущую страницу
		if(page == 1) {
			buttons[0].setVisible(false);
			buttons[1].setVisible(true);
		}
		else
			if(page == totalPages) {
				buttons[0].setVisible(true);
				buttons[1].setVisible(false);
			}
			else {
				buttons[0].setVisible(true);
				buttons[1].setVisible(true);
			}
		updateButtonsStatus(tab);
	}
	
	/**
	 * Функция чтения из файла в место для текста
	 * @param textBrowser место для текста
	 * @param pathToFile путь к файлу
	 * @param newTab вкладка, в которую выводится текст
	 * @param buttonsComposite здесь находятся кнопки
	 */
	private void readFromFileToTextBrowser(Composite textFolder, String pathToFile, CTabItem newTab) {
		Thread curThread = ((Thread) newTab.getData(RUNNINGTHREAD_NAME)); // если поток запущен
		if(curThread != null && !curThread.isInterrupted()) // прерываем его работу
			curThread.interrupt();
		
		if(newTab.getData(PAGE_TEXT) == null) // если мы ещё не указывали номер страницы
			newTab.setData(PAGE_TEXT, 1); // указываем номер страницы
		int page = (int)newTab.getData(PAGE_TEXT); 
		int totalPages = ((int)((new File(pathToFile).length()) / maxCapacity)+1);
		
		if(newTab.getData(TOTALPAGES_TEXT) == null)  // если ещё не указывали, сколько страниц в файле
			newTab.setData(TOTALPAGES_TEXT, totalPages);  // указываем количество страниц в файле
		
		if(totalPages > 1)
			updateButtonsStatus(newTab); // выводим информацию на экран
		
		StyledText textBrowser = createNewTextBrowser(textFolder);
		
		newTab.setData(RUNNINGTHREAD_NAME,new Thread(() -> { // запускаем новый поток и запоминаем его
			byte[] bts = new byte[maxElems]; // массив байт, считанных из файла
			try(RandomAccessFile f = new RandomAccessFile(pathToFile, "r")){ // начинаем читать из файла
				int fileMapSize = 0;
				fileMapSize  = (totalPages == 1) ? (int)f.length() : (page < totalPages) ? maxCapacity : (int)f.length() - (page - 1) * maxCapacity;
				MappedByteBuffer buffer = f.getChannel().map(MapMode.READ_ONLY, (page - 1) * maxCapacity, fileMapSize);
				// пока поток не завершён и можно читать из файла
				while(!Thread.currentThread().isInterrupted() && buffer.hasRemaining()) {
					while(curChars > maxCharsAtOneMoment) { // пока считанных символов больше, чем может быть во вкладках
						Thread.sleep(500); // спим
					}
					if(buffer.remaining() < maxElems)
						bts = new byte[buffer.remaining()];
					buffer.get(bts);
					appendStr(textBrowser, bts); // добавляем строку к тексту 
				}
				Arrays.fill(bts, (byte)0); // заполняем массив нулями
				Display.getDefault().syncExec(() -> {
					if(!newTab.isDisposed())
						newTab.setImage(null); 
				}); // убираем картинку загрузки
			}
			catch(IOException e){ } catch (InterruptedException e) { }
		}));
		((Thread) newTab.getData(RUNNINGTHREAD_NAME)).start(); // запускаем поток
	}

	/**
	 * Функция добавления строки к тексту
	 * @param textBrowser Куда выводим текст
	 * @param byteStr Здесь хранится строка
	 */
	private void appendStr(StyledText textBrowser, byte[] byteStr) {
		Display.getDefault().syncExec(() -> {
			if(textBrowser != null && !textBrowser.isDisposed()) { // если есть, куда выводить
				textBrowser.append(new String(byteStr)); // добавляем новую строку
				curChars += byteStr.length;
			}
		});
	}
	
	/**
	 * Функция вывода тултипа
	 * @param control Куда будет выведен тултип
	 * @param message Сообщение
	 */
	private static void showTooltip(Control control, String message) {
		ToolTip inform = new ToolTip(control.getShell(), SWT.BALLOON | SWT.ICON_ERROR);
		inform.setText(message);
		inform.setMessage(" ");
		inform.setLocation(control.toDisplay(control.getBounds().width, control.getBounds().height));
		inform.setAutoHide(true);
		inform.setVisible(true);
	}
		
	/** Функция создания дерева файловой системы и хранилища вкладок  */
	private void createBody(Shell shell) {
		SashForm body = new SashForm(shell, SWT.HORIZONTAL); // используем SashForm для того, чтобы иметь возможность 
															// изменять размеры виджета
		body.setLayoutData(new GridData(GridData.FILL_BOTH));
        createFileSystemTree (body); // создаём дерево файловой системы
        createTabFolder(shell, body); // создаём хранилище вкладок
	}
	
	/**
	 * Функция создания дерева файловой системы
	 * @param parent
	 */
	private void createFileSystemTree(Composite parent) {
        Composite treeComposite = new Composite(parent, SWT.NONE);
        treeComposite.setLayout(new GridLayout());
        treeComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
        new Label(treeComposite, SWT.NONE).setText("Найденные файлы");

        fileSystemTree = new Tree(treeComposite, SWT.BORDER); // создаём дерево
        fileSystemTree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        fileSystemTree.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent e) { // если пользователь нажал на кнопку мыши
				if(fileSystemTree.getItem(new Point(e.x, e.y)) == null) // если никуда не попали мышкой
					fileSystemTree.deselectAll(); // снимаем выделение 
			}

			@Override
			public void mouseDoubleClick(MouseEvent e) { // отслеживание двойного клика
				TreeItem selectedItem = fileSystemTree.getItem(new Point(e.x, e.y)); // получаем Item по координатам мыши
				// если попали по Item и это файл, то открываем его в новой вкладке
				if(selectedItem != null && selectedItem.getText().charAt(selectedItem.getText().length() - 1) != '\\')
					openFileInNewTab((String)selectedItem.getData(PATH_TEXT), (long)selectedItem.getData(OFFSET_TEXT));
			}

			@Override
			public void mouseDown(MouseEvent e) { }
		});
        // создаём выпадающее меню для дерева, вызываемое по нажатию правой кнопки мыши
        createFileSystemTreeMenu();
    }	
	
	/** Фукнция создания выпадающего меню для дерева файловой системы */
	private void createFileSystemTreeMenu() {
		Menu treeMenu = new Menu(fileSystemTree); // создаём меню
		fileSystemTree.setMenu(treeMenu);	// привязываем его
		treeMenu.addMenuListener(new MenuAdapter() {
			@Override
			public void menuShown(MenuEvent e) { // при показе меню
				MenuItem[] items = treeMenu.getItems();
				for(int i = 0; i < items.length; i++) // уничтожаем существующие элементы меню
					items[i].dispose();
				
				TreeItem[] selectedTreeItems = fileSystemTree.getSelection(); // получаем выделенный элемент дерева
				
				// если он не выделен, или выделено несколько - выходим
				if(selectedTreeItems.length == 0 || selectedTreeItems.length > 1) 
					return;
				
				// если у выделенного элемента есть дочерние
				if(selectedTreeItems[0].getItemCount() > 0) {
					// создаём пункт меню для полного раскрытия дерева
					MenuItem newItemExpand = new MenuItem(treeMenu, SWT.NONE); 
					newItemExpand.setText("Развернуть");
					newItemExpand.addSelectionListener(new SelectionAdapter() {
						@Override
						public void widgetSelected(SelectionEvent e) { // при нажатии на этот пункт
							expandOrCollapseFileSystemTree(fileSystemTree.getSelection()[0], true); // раскрываем дерево
						}
					});
					
					// создаём пункт меню для полного сворачивания дерева до текущего элемента
					MenuItem newItemCollapse = new MenuItem(treeMenu, SWT.NONE);
					newItemCollapse.setText("Свернуть");
					newItemCollapse.addSelectionListener(new SelectionAdapter() {
						@Override
						public void widgetSelected(SelectionEvent e) { // при нажатии
							expandOrCollapseFileSystemTree(fileSystemTree.getSelection()[0], false); // сворачиваем дерево
						}
					});
				}
				
				if(selectedTreeItems[0].getItemCount() == 0) { // если у элемента нет дочерних
					// создаём пункт меню для открытия файла
					MenuItem newItemOpenFile = new MenuItem(treeMenu, SWT.NONE);
					newItemOpenFile.setText("Открыть файл");
					newItemOpenFile.addSelectionListener(new SelectionAdapter() {
						@Override
						public void widgetSelected(SelectionEvent e) {
							openFileInNewTab((String)selectedTreeItems[0].getData(PATH_TEXT), (long)selectedTreeItems[0].getData(OFFSET_TEXT)); // открываем файл, передавая путь к файлу
						}
					});
				}
			}
		});
	}
	
	/**
	 * Рекурсивная функция раскрытия или сворачивания дерева
	 * @param expand Раскрыть дерево - true; Свернуть дерево - false
	 */
	private void expandOrCollapseFileSystemTree(TreeItem item, boolean expand) {		
		item.setExpanded(expand); // раскрываем или сворачиваем выбранный элемент
		
		TreeItem[] childItems = item.getItems(); // получаем всех потомков
		for(TreeItem childItem : childItems) // каждого из них раскрываем или сворачиваем
			expandOrCollapseFileSystemTree(childItem, expand);
	}
	
	/**
	 * Функция создания пространства с вкладками
	 * @param parent
	 */
	private void createTabFolder(Shell shell, Composite parent) {
        Composite tabFolderComposite = new Composite(parent, SWT.NONE);
        tabFolderComposite.setLayout(new GridLayout());
        tabFolderComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
        createTop(shell, tabFolderComposite);
        
        new Label(tabFolderComposite, SWT.NONE).setText("Найденные файлы");
        
        tabFolder = new CTabFolder(tabFolderComposite, SWT.BORDER);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }
	
	/** Функция обновления надписи о текущей странице */
	void updateButtonsStatus(CTabItem tab) {
		buttonsStatusText.setText("Страница " + (int)tab.getData(PAGE_TEXT) + "/" + (int)tab.getData(TOTALPAGES_TEXT)); // выводим их пользователю
		buttonsStatusText.pack(); // изменяем размер Label
		buttonsStatusText.getParent().pack();
	}
	
	/**
	 * Функция, открывающая окно для выбора директории и при успешном 
	 * выборе выводит название директории в названии главного окна
	 */
	private void openFindPathWindow(Shell shell) {
		DirectoryDialog directoryDialog = new DirectoryDialog(shell);
		String newPath;
		if((newPath = directoryDialog.open()) != null) {
			selectedPath = newPath;
			shell.setText(windowTitle + " - " + selectedPath); // выводим в название окна текущую директорию
		}
	}
	
	/**
	 * Функция запуска поиска файлов
	 * @param inputText Текст, который необходимо найти
	 * @param inputExtension Искомое расширение файла
	 */
	private void startFindFiles(Text inputText, Text inputExtension) {
		if(threadPool != null) // останавливаем потоки
			threadPool.shutdownNow();
		threadPool = Executors.newFixedThreadPool(3); // устанавливаем фиксированый размер пула потоков
		FindFiles.getInstance().stopSearch(); // останавливаем текущий поиск файлов

		String textToFind = inputText.getText(); // получаем текст, который надо найти
		String extension = inputExtension.getText().replaceAll("[\\\\/:?\"<>|]", ""); // убираем из расширения ненужные символы
		inputExtension.setText(extension); // получаем расширение
		
		if(extension == "") // если расширение не введено
			showTooltip(inputExtension, "Необходимо ввести расширение!"); // выводим тултип с ошибкой
		else
			if(textToFind != "") { // если текст введён
				changeButton(true); // изменяем текст в кнопке поиска
				
				fileSystemTree.removeAll(); // очищаем дерево файловой системы
				
				threadPool.execute(() -> { // запускаем поток поиска файлов
					FindFiles.getInstance().findFilesInDirectory(textToFind, selectedPath, "." + extension);
				});
				
				threadPool.execute(() -> { // запускаем поток обновления статусбара каждые 0.5 сек.
					do {
						updateStatusBar();
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) { }
					}
					while(!Thread.currentThread().isInterrupted() && FindFiles.getInstance().processing);
					updateStatusBar();
				});
				
				threadPool.execute(() -> { // запускаем поток обновления дерева файловой системы
					while(!Thread.currentThread().isInterrupted() && (FindFiles.getInstance().processing || !FindFiles.getInstance().isQueueEmpty())) 
						if(!FindFiles.getInstance().isQueueEmpty())
							Display.getDefault().syncExec(() -> {
								if(!Thread.currentThread().isInterrupted())
									updateFileSystemTree(FindFiles.getInstance().getFromQueue());
							});
					Display.getDefault().syncExec(() -> {
						if(!Thread.currentThread().isInterrupted()) {
							changeButton(false);
							threadPool.shutdownNow();
						}
					});
				});
			}	
			else 
				showTooltip(inputText, "Необходимо ввести текст!"); // выводим тулбар с ошибкой
	}	
	
	/**
	 * Обновление дерева файловой системы
	 * @param path Путь к файлу
	 */
	private synchronized void updateFileSystemTree(FindedFile findedFile) {
		Path path = findedFile.pathToFile;
		if(path == null || fileSystemTree == null || (fileSystemTree != null && fileSystemTree.isDisposed()) 
				|| Thread.currentThread().isInterrupted())
			return;
		String[] pathToFile = path.toString().split("\\\\"); // делим строку по \\

		TreeItem treeItem = null;
		TreeItem[] treeItems = fileSystemTree.getItems(); // получаем все корневые элементы
		if(treeItems.length == 0) { // если корневых элементов нет
			treeItem =  new TreeItem(fileSystemTree, SWT.NONE); // создаём корневой элемент
			treeItem.setText(pathToFile[0] + "\\");
			for(int i = 1; i < pathToFile.length; i++) {
				treeItem = new TreeItem(treeItem, 0);
				treeItem.setText(pathToFile[i] + "\\");
			}
		}
		else { // обходим дерево и проверяем, есть ли совпадения путей
			treeItem = treeItems[0];
			int k = 0;
			for(int i = 0; i < treeItems.length  && k < pathToFile.length; ) {
				if(treeItems[i].getText().lastIndexOf('\\') != -1 && 
						treeItems[i].getText().substring(0, treeItems[i].getText().lastIndexOf('\\')).equals(pathToFile[k])) {
					treeItem = treeItems[i];
					treeItems = treeItems[i].getItems();
					i = 0;
					k++;
				}
				else
					i++;
			}
			
			// добавляем оставшиеся элементы в дерево
			for(; k < pathToFile.length; k++) {
				treeItem = new TreeItem(treeItem, SWT.NONE);
				treeItem.setText(pathToFile[k] + "\\");
			}
		}
		treeItem.setText(treeItem.getText().substring(0, treeItem.getText().length() - 1));	 // удаляем из текста файла \\
		treeItem.setData(PATH_TEXT, path.toString()); // сохраняем путь к файлу
		treeItem.setData(OFFSET_TEXT, findedFile.offset);
	}
	
	/**
	 * Функция обновления статусбара
	 */
	private synchronized void updateStatusBar() {
		if(!Thread.currentThread().isInterrupted())
			Display.getDefault().asyncExec(() -> {
				if(Thread.currentThread().isInterrupted() || toolBarText == null || 
						(toolBarText != null && toolBarText.isDisposed()))
					return;
				FindFiles instance = FindFiles.getInstance();
			toolBarText.setText("Файлов обрабатывается: " + instance.filesInProgressCount + 
								". Файлов обработано: " + instance.filesDoneCount + 
								". Времени затрачено: " + (System.nanoTime() - instance.startedTime)/1_000_000_000 + " сек.");
			if(!instance.processing)
				toolBarText.setText(toolBarText.getText() + " Поиск завершён.");
			});
	}
}