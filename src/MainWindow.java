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
						 STARTSEARCHTEXT = "������ �����", 
						 STOPSEARCHTEXT = "���������� �����";
	/** ��������� ���� ��� ������ */
	private String selectedPath;
	/** �������� ���� */
	private String windowTitle = "Text Finder";
	/** ������ �������� ������� */
	private Tree fileSystemTree;
	/** ��������� ������� � ������� ����� */
	private CTabFolder tabFolder;
	/** ������ ������ ������ */
	private Button searchButton;
	/** ������ */
	private Label toolBarText;
	/** ���� �������, ������� ������� �������� */
	private Label buttonsStatusText;
	/** ����� ���������:
			- ����� ������ � ����������
			- ���������� ����������
			- ���������� ������ �������� �������  */
	private ExecutorService threadPool;
	
	private final int maxElems = 20_000_000, // ���������� ����, �������� �� 1 ���
			maxCapacity = 100_000_000; // ������������ ���������� ����, ������� ����� �������� �� ���� ��������
	
	private volatile int maxCharsAtOneMoment = 400_000_000;
	private volatile int curChars = 0;
	
	/** ����������� �������� ���� ���������� */
	public MainWindow() {		
		final Display display = Display.getDefault();	
		final Shell shell = new Shell();
		shell.setSize(800, 600); // ������������� ��������� ������ ����
		shell.setText(windowTitle); // ������������� �������� ����
		shell.setMinimumSize(400, 300); // ���������� ����������� ������ ����
		shell.setLayout(new GridLayout());		
		
		// ������������ ���������� ������ shell
		shell.addDisposeListener(new DisposeListener() {
			@Override
			public void widgetDisposed(DisposeEvent e) {
				FindFiles.getInstance().stopSearch();
				if(threadPool != null)
					threadPool.shutdownNow();
			}
		});
		
		createBody(shell); // ������ ����������� ����� ���� � Tree � ����������� ��� �������
		createStatusBar(shell); // ������ �������������� ������
		
		shell.open();
		while (!shell.isDisposed()) 
			if (!display.isDisposed() && !display.readAndDispatch()) 
				display.sleep();
		
		if(display != null && !display.isDisposed()) 
			display.dispose();
	}
	
	/**
	 * ������� �������� "��������" ���� - ���� ��� ����� ������, ����������, ������ ������ � �.�.
	 */
	private void createTop(Shell shell, Composite parent) {
	        Composite header = new Composite (parent, SWT.NONE);
	        
	        header.setLayout(new GridLayout(4, false));
	        header.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
	        
	        new Label(header, SWT.NONE).setText("�����");
	        
	        Text inputText = new Text(header, SWT.BORDER | SWT.SINGLE); // ���� ��� ����� ������
	        GridData gridData = new GridData(GridData.FILL, GridData.CENTER, true, false);
	        gridData.horizontalSpan = 2;
	        inputText.setLayoutData(gridData);

	        Label label1 = new Label(header, SWT.HORIZONTAL); // ������ ������ ����� ����������
	        label1.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, false));
	        label1.setImage(new Image(Display.getDefault(),".\\img\\folder.png"));
	        label1.setToolTipText("����� ����� ����������");
	        label1.addMouseListener(new MouseAdapter() { // ��� ������� ������� ���� - 
	        	@Override								// �������� ����� ����������
	        	public void mouseDown(MouseEvent e) {
	        		openFindPathWindow(shell); 
	        	}
	        });
	        new Label(header, SWT.NONE).setText("����������: *.");
	        
	        Text inputExtension = new Text(header, SWT.BORDER | SWT.SINGLE); // ���� ��� ����� ����������
	        inputExtension.setText("log"); // ���������� ���� ����� *.log
	        inputExtension.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
	        
	        searchButton = new Button(header, SWT.NONE); // ������ ������ ������
	        searchButton.setText(STARTSEARCHTEXT);
	        searchButton.addSelectionListener(new SelectionAdapter() {
	        	@Override
	        	public void widgetSelected(SelectionEvent e) { // ��� ������� �� ������
		        	if(searchButton.getText().equals(STARTSEARCHTEXT)) { // ���� �� �� � �������� ������
		        		if(selectedPath == null) { // ���� ���� �� ������
		        			openFindPathWindow(shell); // ��������� ���� ������ ����
		        			if(selectedPath == null) { // ���� ���� �� ��� ��� �� ������
		        				showTooltip(label1, "���������� ������� ����!"); // ������� � ������� tooltip
		        				return;
		        			}
		        		}
		        		startFindFiles(inputText, inputExtension); // �������� ������ �����
	        		} 
		        	else {
		        		changeButton(false); // ������ ������� �� ������
		        		FindFiles.getInstance().stopSearch(); // ��������� �����
		        		if(threadPool != null) // ��������� ���������� ������
		        			threadPool.shutdownNow();
		        		updateStatusBar(); // ��������� ���������
		        	}	        		
	        	}
	        });	        
	}
	
	/** ������� ��������� ������� �� ������ */
	private void changeButton(boolean searchStarted) {
		if(searchButton != null && !searchButton.isDisposed()) {
			if(searchStarted) 
				searchButton.setText(STOPSEARCHTEXT);
			else 
				searchButton.setText(STARTSEARCHTEXT);
			searchButton.pack(); // �������� ������ ������ ��� ����� �����
		}
	}
	
	/**
	 * ������� �������� ����������
	 */
	private void createStatusBar(Shell shell) {
		toolBarText = new Label(shell, SWT.NONE);
		toolBarText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
		toolBarText.setText(" ");
	}

	/**
	 * ������� �������� ����� � ����� �������
	 * @param pathToFile
	 */
	private void openFileInNewTab(String pathToFile, long offset) {
		for(int i = 0; i < tabFolder.getItemCount(); i++) // ���� ����� ���� ��� ������, �� ������ ��� �� �������
			if(tabFolder.getItem(i).getData(PATH_TEXT).toString().equals(pathToFile))
				return;
		CTabItem newTab = new CTabItem(tabFolder, SWT.CLOSE); // ������ ����� �������
		
		Composite compositePage = new Composite(tabFolder, SWT.NONE);
		compositePage.setLayout(new GridLayout());
		
		Composite textFolder = new Composite(compositePage, SWT.NONE);
		textFolder.setLayout(new GridLayout());
		textFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true)); // ����������� ���

		newTab.setData(PATH_TEXT, pathToFile); // ���������� ���� � �����
		newTab.setData(OFFSET_TEXT, offset);
		newTab.setText(pathToFile.substring(pathToFile.lastIndexOf("\\") + 1, pathToFile.length())); // ������� ��������
		newTab.setImage(new Image(Display.getDefault(), ".\\img\\tabLoading.gif"));
		newTab.addDisposeListener(new DisposeListener() { 
			@Override
			public void widgetDisposed(DisposeEvent e) { // ���� ������ ���������
				Thread fileOpener = ((Thread) newTab.getData(RUNNINGTHREAD_NAME)); // �������� ����� ������ �����
				if(fileOpener != null && !fileOpener.isInterrupted()) { // ���� �� ��������
					fileOpener.interrupt(); // ���������� ��� ������
				}
				StyledText text = ((StyledText) textFolder.getChildren()[0]);
				curChars -= text.getCharCount();
				text.setText(" ");
				text.dispose(); // ������� ����� ��� ������
				textFolder.dispose();

				compositePage.dispose();
				newTab.dispose();
			}
		});
		tabFolder.setSelection(newTab); // ��������� ����� �������
		
		// ���� ������ 1 ������� � �����
		if((int)((new File(pathToFile).length()) / maxCapacity) > 0) 
			createPagesButtons(compositePage, textFolder, true); // ������ ������ �������� �� ����� �������
		else
			createPagesButtons(compositePage, textFolder, false);
		newTab.setControl(compositePage);
		
		readFromFileToTextBrowser(textFolder, pathToFile, newTab); // ������� ����
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
		StyledText textBrowser = new StyledText(textFolder, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY); // ������ ����� ��� ������ � ����� �������
		
		textBrowser.setLayout(new GridLayout());
		textBrowser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true)); // ����������� ���
		textBrowser.setText("");
		textBrowser.setSize(textFolder.getSize());
		textBrowser.addKeyListener(new KeyListener() {
			@Override
			public void keyPressed(KeyEvent e) { // ������� ������� ������
				if(e.character == 0x01)  // ��������� Ctrl+A
					textBrowser.selectAll(); // �������� ���� �����
				else
				if(e.keyCode == SWT.HOME) // ������ Home
					textBrowser.setSelection(0); // ��������� � ������
				else
				if(e.keyCode == SWT.END) // ������ End
					textBrowser.setSelection(textBrowser.getCharCount()); // ��������� � �����
			}
			@Override
			public void keyReleased(KeyEvent e) {}
		});
		return textBrowser;
	}
	
	/** ������� �������� ������ �������������� ������� ��� ������� */
	private void createPagesButtons(Composite parent, Composite textFolder, boolean needToMakeButtons) {
        Composite buttonsComposite = new Composite(parent, SWT.NONE);
        buttonsComposite.setLayout(new GridLayout(4, false));
        GridData gridData = new GridData(SWT.FILL, SWT.FILL, true, false);
        buttonsComposite.setLayoutData(gridData);
        final Label buttonBack; // ������ "�����"
        final Label buttonForward; // ������ "�����"
        if(needToMakeButtons) {
	        buttonBack = new Label(buttonsComposite, SWT.NONE); // ������ "�����"
	        buttonForward = new Label(buttonsComposite, SWT.NONE); // ������ "�����"
	        buttonsStatusText = new Label(buttonsComposite, SWT.NONE); // label ������ �������
	        buttonsStatusText.setText("��������:");
	        
	        buttonBack.setImage(new Image(Display.getDefault(), ".\\img\\back.png"));
	        buttonBack.setVisible(false); // �������� ������ ����� (�� �� ����� ����� �� 1 ��������)
	        buttonBack.setToolTipText("������� �� ���������� ��������");
	        buttonBack.addMouseListener(new MouseAdapter() {
	        	@Override
	        	public void mouseDown(MouseEvent e) { // ��� ������� �� ������ "�� �������� �����"
	        		CTabItem selectedTab = tabFolder.getSelection(); 
	        		if(selectedTab != null) {
	        			// ��������� ����� �� ���� ��������
	        			openPage(selectedTab, (int)selectedTab.getData(PAGE_TEXT) - 1, new Label[] {buttonBack, buttonForward});
	        			readFromFileToTextBrowser(textFolder, (String)selectedTab.getData(PATH_TEXT), selectedTab); // ������� ����� �������� �� �����
	        		}
	        	}
	        });        
	        
	        buttonForward.setImage(new Image(Display.getDefault(), ".\\img\\forward.png"));
	        buttonForward.setToolTipText("������� �� ��������� ��������");
	        buttonForward.addMouseListener(new MouseAdapter() {
	        	@Override
	        	public void mouseDown(MouseEvent e) { // ��� ������� �� ������ "�� �������� �����"
	        		CTabItem selectedTab = tabFolder.getSelection();
	        		if(selectedTab != null) {
	        			// ��������� ����� �� ���� �������
	        			openPage(selectedTab, (int)selectedTab.getData(PAGE_TEXT) + 1, new Label[] {buttonBack, buttonForward});
	        			readFromFileToTextBrowser(textFolder, (String)selectedTab.getData(PATH_TEXT), selectedTab); // ������� ����� �������� �� �����
	        		}
	        	}
	        });
        } 
        else {
        	buttonBack = null;
            buttonForward = null;
        }
        Label buttonGoToText = new Label(buttonsComposite, SWT.NONE); // ������ "������� � ������"
        buttonGoToText.setImage(new Image(Display.getDefault(), ".\\img\\goToString.png"));
        buttonGoToText.setToolTipText("������� � ���������� ������");
        buttonGoToText.addMouseListener(new MouseAdapter() {
        	@Override
        	public void mouseDown(MouseEvent e) { // ��� ������� �� ������ "������� � ������"
        		CTabItem selectedTab = tabFolder.getSelection();
        		long offset = (long)selectedTab.getData(OFFSET_TEXT);
        		int page = (int)selectedTab.getData(PAGE_TEXT);
        		if(page != (int)(offset / maxCapacity) + 1) {
        			openPage(selectedTab, (int)(offset / maxCapacity) + 1, new Label[] {buttonBack, buttonForward});
        			readFromFileToTextBrowser(textFolder, (String)selectedTab.getData(PATH_TEXT), selectedTab); // ������� ����� �������� �� �����
        		}
        		StyledText text = ((StyledText) textFolder.getChildren()[0]);
        		text.setCaretOffset((int) (offset - (int)(offset / maxCapacity) * maxCapacity) - 1);
        		text.setFocus();
        		text.showSelection();
        	}
        });
	}
	
	
	/**
	 * ������� �������� ����� �������� �����
	 * @param textBrowser ����� ��� ������
	 * @param path ���� � �����
	 * @param tab �������, � ������� �������
	 * @param next ��������� ������� - true; ���������� - false
	 * @param buttons 
	 */
	private void openPage(CTabItem tab, int page, Label[] buttons) {
		int totalPages = (int)tab.getData(TOTALPAGES_TEXT); // �������� ������ ���������� �������
		if(page > totalPages || page < 1)
			return;
		tab.setData(PAGE_TEXT, page); // ��������� ������� ��������
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
	 * ������� ������ �� ����� � ����� ��� ������
	 * @param textBrowser ����� ��� ������
	 * @param pathToFile ���� � �����
	 * @param newTab �������, � ������� ��������� �����
	 * @param buttonsComposite ����� ��������� ������
	 */
	private void readFromFileToTextBrowser(Composite textFolder, String pathToFile, CTabItem newTab) {
		Thread curThread = ((Thread) newTab.getData(RUNNINGTHREAD_NAME)); // ���� ����� �������
		if(curThread != null && !curThread.isInterrupted()) // ��������� ��� ������
			curThread.interrupt();
		
		if(newTab.getData(PAGE_TEXT) == null) // ���� �� ��� �� ��������� ����� ��������
			newTab.setData(PAGE_TEXT, 1); // ��������� ����� ��������
		int page = (int)newTab.getData(PAGE_TEXT); 
		int totalPages = ((int)((new File(pathToFile).length()) / maxCapacity)+1);
		
		if(newTab.getData(TOTALPAGES_TEXT) == null)  // ���� ��� �� ���������, ������� ������� � �����
			newTab.setData(TOTALPAGES_TEXT, totalPages);  // ��������� ���������� ������� � �����
		
		if(totalPages > 1)
			updateButtonsStatus(newTab); // ������� ���������� �� �����
		
		StyledText textBrowser = createNewTextBrowser(textFolder);
		
		newTab.setData(RUNNINGTHREAD_NAME,new Thread(() -> { // ��������� ����� ����� � ���������� ���
			byte[] bts = new byte[maxElems]; // ������ ����, ��������� �� �����
			try(RandomAccessFile f = new RandomAccessFile(pathToFile, "r")){ // �������� ������ �� �����
				int fileMapSize = 0;
				fileMapSize  = (totalPages == 1) ? (int)f.length() : (page < totalPages) ? maxCapacity : (int)f.length() - (page - 1) * maxCapacity;
				MappedByteBuffer buffer = f.getChannel().map(MapMode.READ_ONLY, (page - 1) * maxCapacity, fileMapSize);
				// ���� ����� �� �������� � ����� ������ �� �����
				while(!Thread.currentThread().isInterrupted() && buffer.hasRemaining()) {
					while(curChars > maxCharsAtOneMoment) { // ���� ��������� �������� ������, ��� ����� ���� �� ��������
						Thread.sleep(500); // ����
					}
					if(buffer.remaining() < maxElems)
						bts = new byte[buffer.remaining()];
					buffer.get(bts);
					appendStr(textBrowser, bts); // ��������� ������ � ������ 
				}
				Arrays.fill(bts, (byte)0); // ��������� ������ ������
				Display.getDefault().syncExec(() -> {
					if(!newTab.isDisposed())
						newTab.setImage(null); 
				}); // ������� �������� ��������
			}
			catch(IOException e){ } catch (InterruptedException e) { }
		}));
		((Thread) newTab.getData(RUNNINGTHREAD_NAME)).start(); // ��������� �����
	}

	/**
	 * ������� ���������� ������ � ������
	 * @param textBrowser ���� ������� �����
	 * @param byteStr ����� �������� ������
	 */
	private void appendStr(StyledText textBrowser, byte[] byteStr) {
		Display.getDefault().syncExec(() -> {
			if(textBrowser != null && !textBrowser.isDisposed()) { // ���� ����, ���� ��������
				textBrowser.append(new String(byteStr)); // ��������� ����� ������
				curChars += byteStr.length;
			}
		});
	}
	
	/**
	 * ������� ������ �������
	 * @param control ���� ����� ������� ������
	 * @param message ���������
	 */
	private static void showTooltip(Control control, String message) {
		ToolTip inform = new ToolTip(control.getShell(), SWT.BALLOON | SWT.ICON_ERROR);
		inform.setText(message);
		inform.setMessage(" ");
		inform.setLocation(control.toDisplay(control.getBounds().width, control.getBounds().height));
		inform.setAutoHide(true);
		inform.setVisible(true);
	}
		
	/** ������� �������� ������ �������� ������� � ��������� �������  */
	private void createBody(Shell shell) {
		SashForm body = new SashForm(shell, SWT.HORIZONTAL); // ���������� SashForm ��� ����, ����� ����� ����������� 
															// �������� ������� �������
		body.setLayoutData(new GridData(GridData.FILL_BOTH));
        createFileSystemTree (body); // ������ ������ �������� �������
        createTabFolder(shell, body); // ������ ��������� �������
	}
	
	/**
	 * ������� �������� ������ �������� �������
	 * @param parent
	 */
	private void createFileSystemTree(Composite parent) {
        Composite treeComposite = new Composite(parent, SWT.NONE);
        treeComposite.setLayout(new GridLayout());
        treeComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
        new Label(treeComposite, SWT.NONE).setText("��������� �����");

        fileSystemTree = new Tree(treeComposite, SWT.BORDER); // ������ ������
        fileSystemTree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        fileSystemTree.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent e) { // ���� ������������ ����� �� ������ ����
				if(fileSystemTree.getItem(new Point(e.x, e.y)) == null) // ���� ������ �� ������ ������
					fileSystemTree.deselectAll(); // ������� ��������� 
			}

			@Override
			public void mouseDoubleClick(MouseEvent e) { // ������������ �������� �����
				TreeItem selectedItem = fileSystemTree.getItem(new Point(e.x, e.y)); // �������� Item �� ����������� ����
				// ���� ������ �� Item � ��� ����, �� ��������� ��� � ����� �������
				if(selectedItem != null && selectedItem.getText().charAt(selectedItem.getText().length() - 1) != '\\')
					openFileInNewTab((String)selectedItem.getData(PATH_TEXT), (long)selectedItem.getData(OFFSET_TEXT));
			}

			@Override
			public void mouseDown(MouseEvent e) { }
		});
        // ������ ���������� ���� ��� ������, ���������� �� ������� ������ ������ ����
        createFileSystemTreeMenu();
    }	
	
	/** ������� �������� ����������� ���� ��� ������ �������� ������� */
	private void createFileSystemTreeMenu() {
		Menu treeMenu = new Menu(fileSystemTree); // ������ ����
		fileSystemTree.setMenu(treeMenu);	// ����������� ���
		treeMenu.addMenuListener(new MenuAdapter() {
			@Override
			public void menuShown(MenuEvent e) { // ��� ������ ����
				MenuItem[] items = treeMenu.getItems();
				for(int i = 0; i < items.length; i++) // ���������� ������������ �������� ����
					items[i].dispose();
				
				TreeItem[] selectedTreeItems = fileSystemTree.getSelection(); // �������� ���������� ������� ������
				
				// ���� �� �� �������, ��� �������� ��������� - �������
				if(selectedTreeItems.length == 0 || selectedTreeItems.length > 1) 
					return;
				
				// ���� � ����������� �������� ���� ��������
				if(selectedTreeItems[0].getItemCount() > 0) {
					// ������ ����� ���� ��� ������� ��������� ������
					MenuItem newItemExpand = new MenuItem(treeMenu, SWT.NONE); 
					newItemExpand.setText("����������");
					newItemExpand.addSelectionListener(new SelectionAdapter() {
						@Override
						public void widgetSelected(SelectionEvent e) { // ��� ������� �� ���� �����
							expandOrCollapseFileSystemTree(fileSystemTree.getSelection()[0], true); // ���������� ������
						}
					});
					
					// ������ ����� ���� ��� ������� ������������ ������ �� �������� ��������
					MenuItem newItemCollapse = new MenuItem(treeMenu, SWT.NONE);
					newItemCollapse.setText("��������");
					newItemCollapse.addSelectionListener(new SelectionAdapter() {
						@Override
						public void widgetSelected(SelectionEvent e) { // ��� �������
							expandOrCollapseFileSystemTree(fileSystemTree.getSelection()[0], false); // ����������� ������
						}
					});
				}
				
				if(selectedTreeItems[0].getItemCount() == 0) { // ���� � �������� ��� ��������
					// ������ ����� ���� ��� �������� �����
					MenuItem newItemOpenFile = new MenuItem(treeMenu, SWT.NONE);
					newItemOpenFile.setText("������� ����");
					newItemOpenFile.addSelectionListener(new SelectionAdapter() {
						@Override
						public void widgetSelected(SelectionEvent e) {
							openFileInNewTab((String)selectedTreeItems[0].getData(PATH_TEXT), (long)selectedTreeItems[0].getData(OFFSET_TEXT)); // ��������� ����, ��������� ���� � �����
						}
					});
				}
			}
		});
	}
	
	/**
	 * ����������� ������� ��������� ��� ������������ ������
	 * @param expand �������� ������ - true; �������� ������ - false
	 */
	private void expandOrCollapseFileSystemTree(TreeItem item, boolean expand) {		
		item.setExpanded(expand); // ���������� ��� ����������� ��������� �������
		
		TreeItem[] childItems = item.getItems(); // �������� ���� ��������
		for(TreeItem childItem : childItems) // ������� �� ��� ���������� ��� �����������
			expandOrCollapseFileSystemTree(childItem, expand);
	}
	
	/**
	 * ������� �������� ������������ � ���������
	 * @param parent
	 */
	private void createTabFolder(Shell shell, Composite parent) {
        Composite tabFolderComposite = new Composite(parent, SWT.NONE);
        tabFolderComposite.setLayout(new GridLayout());
        tabFolderComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        
        createTop(shell, tabFolderComposite);
        
        new Label(tabFolderComposite, SWT.NONE).setText("��������� �����");
        
        tabFolder = new CTabFolder(tabFolderComposite, SWT.BORDER);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }
	
	/** ������� ���������� ������� � ������� �������� */
	void updateButtonsStatus(CTabItem tab) {
		buttonsStatusText.setText("�������� " + (int)tab.getData(PAGE_TEXT) + "/" + (int)tab.getData(TOTALPAGES_TEXT)); // ������� �� ������������
		buttonsStatusText.pack(); // �������� ������ Label
		buttonsStatusText.getParent().pack();
	}
	
	/**
	 * �������, ����������� ���� ��� ������ ���������� � ��� �������� 
	 * ������ ������� �������� ���������� � �������� �������� ����
	 */
	private void openFindPathWindow(Shell shell) {
		DirectoryDialog directoryDialog = new DirectoryDialog(shell);
		String newPath;
		if((newPath = directoryDialog.open()) != null) {
			selectedPath = newPath;
			shell.setText(windowTitle + " - " + selectedPath); // ������� � �������� ���� ������� ����������
		}
	}
	
	/**
	 * ������� ������� ������ ������
	 * @param inputText �����, ������� ���������� �����
	 * @param inputExtension ������� ���������� �����
	 */
	private void startFindFiles(Text inputText, Text inputExtension) {
		if(threadPool != null) // ������������� ������
			threadPool.shutdownNow();
		threadPool = Executors.newFixedThreadPool(3); // ������������� ������������ ������ ���� �������
		FindFiles.getInstance().stopSearch(); // ������������� ������� ����� ������

		String textToFind = inputText.getText(); // �������� �����, ������� ���� �����
		String extension = inputExtension.getText().replaceAll("[\\\\/:?\"<>|]", ""); // ������� �� ���������� �������� �������
		inputExtension.setText(extension); // �������� ����������
		
		if(extension == "") // ���� ���������� �� �������
			showTooltip(inputExtension, "���������� ������ ����������!"); // ������� ������ � �������
		else
			if(textToFind != "") { // ���� ����� �����
				changeButton(true); // �������� ����� � ������ ������
				
				fileSystemTree.removeAll(); // ������� ������ �������� �������
				
				threadPool.execute(() -> { // ��������� ����� ������ ������
					FindFiles.getInstance().findFilesInDirectory(textToFind, selectedPath, "." + extension);
				});
				
				threadPool.execute(() -> { // ��������� ����� ���������� ���������� ������ 0.5 ���.
					do {
						updateStatusBar();
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) { }
					}
					while(!Thread.currentThread().isInterrupted() && FindFiles.getInstance().processing);
					updateStatusBar();
				});
				
				threadPool.execute(() -> { // ��������� ����� ���������� ������ �������� �������
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
				showTooltip(inputText, "���������� ������ �����!"); // ������� ������ � �������
	}	
	
	/**
	 * ���������� ������ �������� �������
	 * @param path ���� � �����
	 */
	private synchronized void updateFileSystemTree(FindedFile findedFile) {
		Path path = findedFile.pathToFile;
		if(path == null || fileSystemTree == null || (fileSystemTree != null && fileSystemTree.isDisposed()) 
				|| Thread.currentThread().isInterrupted())
			return;
		String[] pathToFile = path.toString().split("\\\\"); // ����� ������ �� \\

		TreeItem treeItem = null;
		TreeItem[] treeItems = fileSystemTree.getItems(); // �������� ��� �������� ��������
		if(treeItems.length == 0) { // ���� �������� ��������� ���
			treeItem =  new TreeItem(fileSystemTree, SWT.NONE); // ������ �������� �������
			treeItem.setText(pathToFile[0] + "\\");
			for(int i = 1; i < pathToFile.length; i++) {
				treeItem = new TreeItem(treeItem, 0);
				treeItem.setText(pathToFile[i] + "\\");
			}
		}
		else { // ������� ������ � ���������, ���� �� ���������� �����
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
			
			// ��������� ���������� �������� � ������
			for(; k < pathToFile.length; k++) {
				treeItem = new TreeItem(treeItem, SWT.NONE);
				treeItem.setText(pathToFile[k] + "\\");
			}
		}
		treeItem.setText(treeItem.getText().substring(0, treeItem.getText().length() - 1));	 // ������� �� ������ ����� \\
		treeItem.setData(PATH_TEXT, path.toString()); // ��������� ���� � �����
		treeItem.setData(OFFSET_TEXT, findedFile.offset);
	}
	
	/**
	 * ������� ���������� ����������
	 */
	private synchronized void updateStatusBar() {
		if(!Thread.currentThread().isInterrupted())
			Display.getDefault().asyncExec(() -> {
				if(Thread.currentThread().isInterrupted() || toolBarText == null || 
						(toolBarText != null && toolBarText.isDisposed()))
					return;
				FindFiles instance = FindFiles.getInstance();
			toolBarText.setText("������ ��������������: " + instance.filesInProgressCount + 
								". ������ ����������: " + instance.filesDoneCount + 
								". ������� ���������: " + (System.nanoTime() - instance.startedTime)/1_000_000_000 + " ���.");
			if(!instance.processing)
				toolBarText.setText(toolBarText.getText() + " ����� ��������.");
			});
	}
}