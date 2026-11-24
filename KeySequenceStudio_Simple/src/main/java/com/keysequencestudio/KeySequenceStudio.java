package com.keysequencestudio;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelListener;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Key Sequence Studio - a global keyboard/mouse-trigger sequence editor.
 *
 * Global listeners compare keyboard and mouse-button presses against configured triggers.
 * Sequence actions are sent locally with java.awt.Robot.
 */
public final class KeySequenceStudio extends JFrame implements NativeKeyListener, NativeMouseListener, NativeMouseWheelListener {
    private static final long serialVersionUID = 1L;
    private static final int FILE_VERSION = 15; // Adds If pixel found / End if conditional blocks.
    // Default duration for newly-added key and mouse press steps. Each step can
    // be customized independently in the sequence editor.
    private static final int DEFAULT_HOLD_MS = 20;
    private static final int MAX_HOLD_MS = 5_000;
    private static final int MAX_WAIT_MS = 10_000;
    private static final int MAX_REPEAT_COUNT = 1_000;
    private static final int MAX_BINDS = 500;
    private static final int MAX_STEPS_PER_BIND = 2_000;
    private static final int MAX_TRIGGERS_PER_BIND = 8;
    private static final int MAX_MACRO_LOOP_DEPTH = 5;
    private static final int MAX_MACRO_EXECUTED_BLOCKS = 100_000;

    private final DefaultListModel<Bind> bindListModel = new DefaultListModel<>();
    private final DefaultListModel<Step> stepListModel = new DefaultListModel<>();
    private final JList<Bind> bindList = new JList<>(bindListModel);
    private final JList<Step> stepList = new JList<>(stepListModel);
    private final DefaultListModel<Macro> macroListModel = new DefaultListModel<>();
    private final JList<Macro> macroList = new JList<>(macroListModel);
    private final DefaultListModel<MacroBlock> macroBlockListModel = new DefaultListModel<>();
    private final JList<MacroBlock> macroBlockList = new JList<>(macroBlockListModel);
    private JPanel macroBlockWorkspaceHolder;
    private JScrollPane macroBlockScroll;
    private final List<JButton> macroToolboxButtons = new ArrayList<>();
    private int randomReporterMinimum = 1;
    private int randomReporterMaximum = 100;
    private final JLabel macroBlockHeading = new JLabel("MACRO BLOCKS  ·  SELECT A MACRO");
    private final JLabel macroTriggerSummary = new JLabel("WHEN A TRIGGER IS PRESSED");
    private final JSpinner macroRepeatSpinner = new JSpinner(new SpinnerNumberModel(1, 1, MAX_REPEAT_COUNT, 1));
    private boolean updatingMacroRepeatSpinner;
    private final JLabel statusLabel = new JLabel("Ready. Add a bind to get started.");
    private final JTextArea macroActivityLog = new JTextArea(5, 40);
    private final JScrollPane macroActivityScroll = new JScrollPane(macroActivityLog);
    private final JLabel sequenceHeading = new JLabel("SEQUENCE");
    private final CardLayout modeCardLayout = new CardLayout();
    private final JPanel modeCards = new JPanel(modeCardLayout);
    private final JToggleButton sequenceTabButton = new JToggleButton("Sequence");
    private final JToggleButton holdTabButton = new JToggleButton("Hold");
    private final JToggleButton settingsTabButton = new JToggleButton("Settings");
    private final JToggleButton macrosTabButton = new JToggleButton("Macros");
    private final JCheckBox confirmDeleteCheck = new JCheckBox("Confirm before removing binds");
    private final JCheckBox alwaysOnTopCheck = new JCheckBox("Keep this window above other windows");
    private final JCheckBox notificationsEnabledCheck = new JCheckBox("Show run notifications");
    private final JSpinner notificationDurationSpinner = new JSpinner(new SpinnerNumberModel(3000, 500, 15_000, 250));
    private final JComboBox<NotificationPosition> notificationPositionCombo = new JComboBox<>(NotificationPosition.values());
    private final JButton previewNotificationButton = secondaryButton("Preview notification");
    private volatile boolean notificationsEnabled = true;
    private volatile int notificationDurationMs = 3000;
    private volatile NotificationPosition notificationPosition = NotificationPosition.TOP_RIGHT;
    private final List<JWindow> notificationWindows = new ArrayList<>();
    private final Map<JWindow, Timer> notificationTimers = new HashMap<>();
    private boolean loadingSettings;

    // Global hotkey that toggles selected automation groups without changing each
    // bind's individual enabled flag. The app always starts in the enabled state.
    private final JButton setGlobalToggleKeyButton = secondaryButton("Set toggle key");
    private final JButton clearGlobalToggleKeyButton = secondaryButton("Clear");
    private final JLabel globalToggleKeyLabel = new JLabel("Not set");
    private final JLabel globalToggleStateLabel = new JLabel("Currently enabled");
    private final JCheckBox globalToggleSequenceCheck = new JCheckBox("Sequence binds");
    private final JCheckBox globalToggleHoldCheck = new JCheckBox("Hold binds");
    private final JCheckBox globalToggleMacrosCheck = new JCheckBox("Macros");
    private final JButton disableSelectedGroupsButton = secondaryButton("Disable selected");
    private final JButton enableSelectedGroupsButton = secondaryButton("Enable selected");
    private volatile int globalToggleTriggerCode;
    private volatile boolean capturingGlobalToggleKey;
    private volatile boolean globalToggleDisabled;
    private volatile boolean globalToggleAffectsSequence = true;
    private volatile boolean globalToggleAffectsHold = true;
    private volatile boolean globalToggleAffectsMacros = true;
    private volatile Future<?> activeAutomationFuture;
    private volatile AutomationCategory activeAutomationCategory;
    private final Object automationStateLock = new Object();
    private long automationRunCounter;
    private long activeAutomationRunId;

    // The Potato Mode UI now uses built-in presets. These legacy fields remain as the
    // persisted storage fields so existing version-8 save files remain readable.
    private final JTextField potatoGameNameField = new JTextField("Fortnite - Low", 18);
    private final JTextField potatoConfigPathField = new JTextField();
    private final JTextArea potatoOverridesArea = new JTextArea(15, 56);
    private final JComboBox<PotatoPreset> potatoPresetCombo = new JComboBox<>(PotatoPreset.values());
    private final JLabel potatoPresetDescription = new JLabel(" ");

    private final DefaultListModel<HoldBind> holdBindListModel = new DefaultListModel<>();
    private final JList<HoldBind> holdBindList = new JList<>(holdBindListModel);
    private final JLabel holdActionLabel = new JLabel("Action: Not set");
    private final JSpinner holdIntervalSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 10_000, 1));
    private final JSpinner holdPressSpinner = new JSpinner(new SpinnerNumberModel(20, 1, MAX_HOLD_MS, 1));
    private final JButton setHoldActionButton = secondaryButton("Set action");
    private boolean updatingHoldControls;
    private final JSpinner repeatSpinner = new JSpinner(new SpinnerNumberModel(1, 1, MAX_REPEAT_COUNT, 1));
    private final JComboBox<DelayMode> delayModeCombo = new JComboBox<>(DelayMode.values());
    private boolean updatingRepeatSpinner;
    private boolean updatingDelayMode;
    private volatile DelayMode delayMode = DelayMode.PER_STEP;

    private final AtomicBoolean macroRunning = new AtomicBoolean(false);
    private final Set<Integer> physicallyDown = new HashSet<>();
    private final Map<Integer, Bind> triggerMap = new ConcurrentHashMap<>();
    private final Map<Integer, HoldBind> holdTriggerMap = new ConcurrentHashMap<>();
    private final Map<Integer, Macro> macroTriggerMap = new ConcurrentHashMap<>();
    // Robot-generated key-up/button-up events for a Hold action that matches its own
    // trigger must not be mistaken for the user's physical release. Entries expire
    // quickly in case a platform does not report Robot-generated events to JNativeHook.
    private final Map<Integer, Long> selfGeneratedReleaseUntil = new ConcurrentHashMap<>();
    private final ExecutorService macroExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "KeySequence-MacroRunner");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService holdExecutor = Executors.newScheduledThreadPool(4, r -> {
        Thread thread = new Thread(r, "KeySequence-HoldRunner");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean waitingForTrigger;
    private volatile boolean capturingSequenceAction;
    private volatile boolean capturingAdditionalSequenceTrigger;
    private volatile Bind pendingAdditionalSequenceTriggerBind;
    private volatile boolean waitingForHoldTrigger;
    private volatile boolean capturingHoldAction;
    private volatile boolean capturingMacroTrigger;
    private volatile Macro pendingMacroTriggerTarget;
    private volatile boolean creatingMacroFromTrigger;
    private volatile boolean capturingMacroBlockInput;
    private volatile MacroBlock pendingMacroBlockInput;
    private volatile boolean closing;
    private Robot robot;
    private final Path saveFile;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                setSystemLookAndFeel();
                new KeySequenceStudio().setVisible(true);
            } catch (Exception exception) {
                exception.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "Could not start Key Sequence Studio:\n" + exception.getMessage(),
                        "Startup error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static void setSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // The default Swing look and feel remains a readable fallback.
        }
    }

    private KeySequenceStudio() throws AWTException {
        super("Key Sequence Studio");
        robot = new Robot();
        robot.setAutoDelay(0);
        saveFile = Path.of(System.getProperty("user.home"), ".key-sequence-studio", "binds.dat");

        buildUi();
        loadBinds();
        installGlobalKeyListener();

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                shutdown();
            }
        });
        setMinimumSize(new Dimension(820, 540));
        setSize(960, 640);
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(new Color(242, 244, 247));
        root.setBorder(new EmptyBorder(14, 14, 12, 14));
        setContentPane(root);

        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setOpaque(false);
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Key Sequence Studio");
        title.setForeground(new Color(32, 38, 48));
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        JLabel subtitle = new JLabel("Create a trigger for an ordered sequence or a repeat action while held.");
        subtitle.setForeground(new Color(91, 101, 116));
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setBorder(new EmptyBorder(4, 1, 0, 0));
        titleBlock.add(title);
        titleBlock.add(subtitle);
        header.add(titleBlock, BorderLayout.WEST);

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(header, BorderLayout.NORTH);
        north.add(buildModeTabs(), BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        modeCards.setOpaque(false);
        modeCards.add(buildSequenceModePanel(), "sequence");
        modeCards.add(buildHoldModePanel(), "hold");
        modeCards.add(buildMacrosModePanel(), "macros");
        modeCards.add(buildSettingsPanel(), "settings");
        root.add(modeCards, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        statusLabel.setForeground(new Color(79, 89, 104));
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        footer.add(statusLabel, BorderLayout.CENTER);
        JLabel saved = new JLabel("Auto-save on");
        saved.setForeground(new Color(48, 122, 83));
        saved.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        footer.add(saved, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
    }

    private JPanel buildModeTabs() {
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tabs.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        group.add(sequenceTabButton);
        group.add(holdTabButton);
        group.add(macrosTabButton);
        group.add(settingsTabButton);
        styleModeTab(sequenceTabButton);
        styleModeTab(holdTabButton);
        styleModeTab(macrosTabButton);
        styleModeTab(settingsTabButton);
        sequenceTabButton.setSelected(true);
        sequenceTabButton.addActionListener(event -> modeCardLayout.show(modeCards, "sequence"));
        holdTabButton.addActionListener(event -> modeCardLayout.show(modeCards, "hold"));
        macrosTabButton.addActionListener(event -> modeCardLayout.show(modeCards, "macros"));
        settingsTabButton.addActionListener(event -> modeCardLayout.show(modeCards, "settings"));
        tabs.add(sequenceTabButton);
        tabs.add(holdTabButton);
        tabs.add(macrosTabButton);
        tabs.add(settingsTabButton);
        return tabs;
    }

    private void styleModeTab(JToggleButton button) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(190, 199, 211)),
                new EmptyBorder(7, 18, 7, 18)));
        button.setOpaque(true);
        button.setBackground(new Color(235, 239, 245));
        button.setForeground(new Color(40, 50, 64));
        button.addChangeListener(event -> {
            boolean selected = button.isSelected();
            button.setBackground(selected ? new Color(43, 103, 170) : new Color(235, 239, 245));
            button.setForeground(selected ? Color.WHITE : new Color(40, 50, 64));
        });
    }

    private JPanel buildMacrosModePanel() {
        JPanel main = new JPanel(new GridLayout(1, 2, 10, 0));
        main.setOpaque(false);
        main.add(buildMacrosListPanel());
        main.add(buildMacroBlocksPanel());
        return main;
    }

    private JPanel buildMacrosListPanel() {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(0, 10));
        panel.add(sectionHeading("Macros"), BorderLayout.NORTH);
        styleList(macroList);
        macroList.setFixedCellHeight(48);
        macroList.setCellRenderer(new MacroRenderer());
        macroList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) refreshMacroBlocks();
        });
        JScrollPane scroll = new JScrollPane(macroList);
        styleScrollPane(scroll);
        panel.add(scroll, BorderLayout.CENTER);

        JButton add = primaryButton("Add macro");
        add.setToolTipText("Create a macro, then press its first trigger key or mouse button");
        add.addActionListener(event -> beginNewMacroTriggerCapture());
        JButton addTrigger = secondaryButton("Add trigger to selected");
        addTrigger.addActionListener(event -> beginAdditionalMacroTriggerCapture());
        JButton rename = secondaryButton("Rename");
        rename.addActionListener(event -> renameSelectedMacro());
        JButton remove = secondaryButton("Remove");
        remove.addActionListener(event -> removeSelectedMacro());
        JButton toggle = secondaryButton("Enable / disable");
        toggle.addActionListener(event -> toggleSelectedMacro());

        JPanel buttons = new JPanel(new GridLayout(4, 1, 0, 5));
        buttons.setOpaque(false);
        buttons.add(add);
        buttons.add(addTrigger);
        JPanel lower = new JPanel(new GridLayout(1, 3, 5, 0));
        lower.setOpaque(false);
        lower.add(rename);
        lower.add(remove);
        lower.add(toggle);
        buttons.add(lower);
        JLabel hint = sectionHint("A macro can have several trigger keys or mouse buttons. Any one of its triggers starts the block stack.");
        buttons.add(hint);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildMacroBlocksPanel() {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(0, 9));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        macroBlockHeading.setForeground(new Color(42, 51, 64));
        macroBlockHeading.setFont(new Font("Segoe UI", Font.BOLD, 13));
        header.add(macroBlockHeading);
        header.add(Box.createVerticalStrut(7));
        macroTriggerSummary.setOpaque(true);
        macroTriggerSummary.setBackground(new Color(255, 205, 48));
        macroTriggerSummary.setForeground(new Color(75, 51, 0));
        macroTriggerSummary.setFont(new Font("Segoe UI", Font.BOLD, 12));
        macroTriggerSummary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 161, 22), 1),
                new EmptyBorder(10, 14, 10, 14)));
        header.add(macroTriggerSummary);
        header.add(Box.createVerticalStrut(8));

        JPanel repeatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        repeatRow.setOpaque(false);
        repeatRow.add(new JLabel("Repeat stack:"));
        macroRepeatSpinner.setPreferredSize(new Dimension(80, 26));
        macroRepeatSpinner.setEnabled(false);
        macroRepeatSpinner.addChangeListener(event -> updateSelectedMacroRepeat());
        repeatRow.add(macroRepeatSpinner);
        repeatRow.add(new JLabel("times"));
        header.add(repeatRow);
        panel.add(header, BorderLayout.NORTH);

        // Scratch-style toolbox on the left and a connected block stack on the right.
        JPanel editor = new JPanel(new BorderLayout(10, 0));
        editor.setOpaque(false);
        JScrollPane toolboxScroll = new JScrollPane(buildMacroToolboxPanel());
        toolboxScroll.setPreferredSize(new Dimension(185, 360));
        toolboxScroll.setMinimumSize(new Dimension(165, 0));
        toolboxScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        toolboxScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        toolboxScroll.getVerticalScrollBar().setUnitIncrement(20);
        toolboxScroll.getVerticalScrollBar().setBlockIncrement(120);
        toolboxScroll.getViewport().setBackground(new Color(242, 245, 249));
        toolboxScroll.setBorder(BorderFactory.createLineBorder(new Color(218, 224, 232)));
        styleScrollPane(toolboxScroll);
        toolboxScroll.getViewport().setBackground(new Color(242, 245, 249));
        editor.add(toolboxScroll, BorderLayout.WEST);

        JPanel workspace = new JPanel(new BorderLayout(0, 6));
        workspace.setOpaque(false);
        JLabel workspaceTitle = new JLabel("BLOCK WORKSPACE");
        workspaceTitle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        workspaceTitle.setForeground(new Color(88, 99, 113));
        JLabel workspaceHint = new JLabel("Select a block, then add from the toolbox after it · Repeat opens a body · double-click to edit · drag to reorder");
        workspaceHint.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        workspaceHint.setForeground(new Color(112, 122, 136));
        JPanel workspaceHeader = new JPanel(new BorderLayout(0, 2));
        workspaceHeader.setOpaque(false);
        workspaceHeader.add(workspaceTitle, BorderLayout.NORTH);
        workspaceHeader.add(workspaceHint, BorderLayout.SOUTH);
        workspace.add(workspaceHeader, BorderLayout.NORTH);

        styleList(macroBlockList);
        macroBlockList.setBackground(new Color(246, 248, 251));
        macroBlockList.setFixedCellHeight(54);
        macroBlockList.setCellRenderer(new MacroBlockRenderer());
        macroBlockList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        macroBlockList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    int index = macroBlockList.locationToIndex(event.getPoint());
                    if (index >= 0 && macroBlockList.getCellBounds(index, index).contains(event.getPoint())) {
                        macroBlockList.setSelectedIndex(index);
                        editSelectedMacroBlock();
                    }
                }
            }
        });

        macroBlockScroll = new JScrollPane(macroBlockList);
        macroBlockScroll.setBorder(BorderFactory.createLineBorder(new Color(218, 224, 232)));
        macroBlockScroll.getViewport().setBackground(new Color(246, 248, 251));
        styleScrollPane(macroBlockScroll);
        macroBlockWorkspaceHolder = new JPanel(new BorderLayout());
        macroBlockWorkspaceHolder.setBackground(new Color(246, 248, 251));
        macroBlockWorkspaceHolder.setBorder(BorderFactory.createLineBorder(new Color(218, 224, 232)));
        workspace.add(macroBlockWorkspaceHolder, BorderLayout.CENTER);
        editor.add(workspace, BorderLayout.CENTER);
        panel.add(editor, BorderLayout.CENTER);

        installMacroBlockDragAndDrop();

        JPanel controls = new JPanel(new GridLayout(1, 5, 5, 0));
        controls.setOpaque(false);
        JButton editBlock = secondaryButton("Edit selected");
        editBlock.addActionListener(event -> editSelectedMacroBlock());
        JButton up = secondaryButton("Move up");
        up.addActionListener(event -> moveSelectedMacroBlock(-1));
        JButton down = secondaryButton("Move down");
        down.addActionListener(event -> moveSelectedMacroBlock(1));
        JButton remove = secondaryButton("Remove block");
        remove.addActionListener(event -> removeSelectedMacroBlock());
        JButton clearLog = secondaryButton("Clear log");
        clearLog.addActionListener(event -> {
            macroActivityLog.setText("");
            statusLabel.setText("Activity log cleared.");
        });
        controls.add(editBlock);
        controls.add(up);
        controls.add(down);
        controls.add(remove);
        controls.add(clearLog);

        macroActivityLog.setEditable(false);
        macroActivityLog.setFont(new Font("Consolas", Font.PLAIN, 11));
        macroActivityLog.setBackground(new Color(24, 28, 34));
        macroActivityLog.setForeground(new Color(180, 220, 160));
        macroActivityLog.setCaretColor(new Color(180, 220, 160));
        macroActivityLog.setBorder(new EmptyBorder(6, 8, 6, 8));
        macroActivityLog.setLineWrap(true);
        macroActivityLog.setWrapStyleWord(true);
        macroActivityScroll.setPreferredSize(new Dimension(100, 110));
        macroActivityScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(60, 70, 82)),
                "Activity log  ·  what each block does",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 11),
                new Color(140, 160, 140)));
        macroActivityScroll.getViewport().setBackground(new Color(24, 28, 34));
        styleScrollPane(macroActivityScroll);

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(controls, BorderLayout.NORTH);
        south.add(macroActivityScroll, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        refreshMacroBlocks();
        return panel;
    }

    private JPanel buildMacroToolboxPanel() {
        JPanel toolbox = new JPanel();
        toolbox.setLayout(new BoxLayout(toolbox, BoxLayout.Y_AXIS));
        toolbox.setBackground(new Color(242, 245, 249));
        toolbox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(218, 224, 232)),
                new EmptyBorder(9, 8, 9, 8)));
        toolbox.setMinimumSize(new Dimension(150, 0));

        JLabel title = new JLabel("BLOCK TOOLBOX");
        title.setFont(new Font("Segoe UI", Font.BOLD, 10));
        title.setForeground(new Color(90, 101, 115));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        toolbox.add(title);
        toolbox.add(Box.createVerticalStrut(10));

        // Scratch-inspired categories & colors (Motion blue, Looks purple, Sound pink, Control orange, etc.)
        addToolboxCategory(toolbox, "MOTION");
        addToolboxBlock(toolbox, "Move to x y", MacroBlockType.MOVE_MOUSE, new Color(76, 151, 255));
        addToolboxBlock(toolbox, "Move by dx dy", MacroBlockType.MOVE_MOUSE_REL, new Color(76, 151, 255));
        addToolboxBlock(toolbox, "Scroll wheel", MacroBlockType.SCROLL, new Color(76, 151, 255));
        toolbox.add(Box.createVerticalStrut(8));
        addToolboxCategory(toolbox, "LOOKS");
        addToolboxBlock(toolbox, "Show notice", MacroBlockType.NOTIFY, new Color(153, 102, 255));
        toolbox.add(Box.createVerticalStrut(8));
        addToolboxCategory(toolbox, "SOUND");
        addToolboxBlock(toolbox, "Play beep", MacroBlockType.BEEP, new Color(207, 99, 207));
        toolbox.add(Box.createVerticalStrut(8));
        addToolboxCategory(toolbox, "EVENTS / INPUT");
        addToolboxBlock(toolbox, "Press key", MacroBlockType.KEY, new Color(76, 151, 255));
        addToolboxBlock(toolbox, "Click mouse", MacroBlockType.MOUSE, new Color(153, 102, 255));
        addToolboxBlock(toolbox, "Type text", MacroBlockType.TYPE_TEXT, new Color(49, 132, 208));
        toolbox.add(Box.createVerticalStrut(8));
        addToolboxCategory(toolbox, "CONTROL");
        addToolboxBlock(toolbox, "Wait", MacroBlockType.WAIT, new Color(255, 171, 25));
        addToolboxBlock(toolbox, "Repeat", MacroBlockType.REPEAT, new Color(255, 171, 25));
        addToolboxBlock(toolbox, "Stop macro", MacroBlockType.STOP, new Color(211, 76, 76));
        toolbox.add(Box.createVerticalStrut(8));
        addToolboxCategory(toolbox, "SENSING / OTHER");
        addToolboxBlock(toolbox, "Copy text", MacroBlockType.COPY_TEXT, new Color(92, 177, 214));
        addToolboxBlock(toolbox, "If pixel found", MacroBlockType.FIND_PIXEL, new Color(92, 177, 214));
        addToolboxBlock(toolbox, "Look for image", MacroBlockType.FIND_IMAGE, new Color(92, 177, 214));
        addToolboxBlock(toolbox, "End if", MacroBlockType.END_IF, new Color(70, 140, 170));
        addToolboxBlock(toolbox, "Comment", MacroBlockType.COMMENT, new Color(119, 130, 145));
        toolbox.add(Box.createVerticalStrut(8));
        addToolboxCategory(toolbox, "OPERATORS");
        addToolboxBlock(toolbox, "Random 1–100", MacroBlockType.RANDOM_NUMBER, new Color(89, 192, 89));
        toolbox.add(Box.createVerticalStrut(12));
        JLabel tip = new JLabel("<html>Scroll for more blocks. Select Repeat, then add blocks after it to place them inside its loop. Random can be dropped into [ ] number slots.</html>");
        tip.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        tip.setForeground(new Color(110, 120, 133));
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);
        tip.setMaximumSize(new Dimension(140, 80));
        toolbox.add(tip);
        return toolbox;
    }

    private void addToolboxCategory(JPanel toolbox, String name) {
        JLabel label = new JLabel(name);
        label.setFont(new Font("Segoe UI", Font.BOLD, 9));
        label.setForeground(new Color(135, 145, 158));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        toolbox.add(label);
        toolbox.add(Box.createVerticalStrut(5));
    }

    private void addToolboxBlock(JPanel toolbox, String label, MacroBlockType type, Color color) {
        toolbox.add(createToolboxBlockButton(label, type, color));
        toolbox.add(Box.createVerticalStrut(5));
    }

    // Choose near-black or white according to which gives stronger contrast on the block color.
    private static Color contrastTextColor(Color background) {
        double luminance = 0.2126 * linearSrgb(background.getRed())
                + 0.7152 * linearSrgb(background.getGreen())
                + 0.0722 * linearSrgb(background.getBlue());
        Color dark = new Color(25, 31, 42);
        double darkLuminance = 0.2126 * linearSrgb(dark.getRed())
                + 0.7152 * linearSrgb(dark.getGreen())
                + 0.0722 * linearSrgb(dark.getBlue());
        double whiteContrast = 1.05 / (luminance + 0.05);
        double darkContrast = (luminance + 0.05) / (darkLuminance + 0.05);
        return whiteContrast >= darkContrast ? Color.WHITE : dark;
    }

    private static double linearSrgb(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private JButton createToolboxBlockButton(String label, MacroBlockType type, Color color) {
        // Draw the colored button surface ourselves. Some Windows Swing themes ignore
        // custom button backgrounds, which can otherwise leave white text on white.
        JButton button = new JButton(label) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color fill = getBackground();
                    if (getModel().isPressed()) fill = fill.darker();
                    g2.setColor(fill);
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                    g2.setColor(fill.darker());
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(graphics);
            }
        };
        button.setFont(new Font("Segoe UI", Font.BOLD, 10));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setForeground(contrastTextColor(color));
        button.setBackground(color);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setOpaque(false);
        button.setBorder(new EmptyBorder(6, 8, 6, 8));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        button.setToolTipText(type == MacroBlockType.RANDOM_NUMBER
                ? "Drag onto a numbered [ ] slot. Click to edit the random range."
                : "Click to add, or drag into the block workspace");
        button.addActionListener(event -> {
            if (type == MacroBlockType.RANDOM_NUMBER) editRandomReporterDefaults();
            else addMacroBlock(type);
        });
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) {
                if (type == MacroBlockType.RANDOM_NUMBER) {
                    button.setText("Random " + randomReporterMinimum + "–" + randomReporterMaximum);
                }
            }
        });
        button.setTransferHandler(new TransferHandler() {
            @Override public int getSourceActions(JComponent component) { return COPY; }
            @Override protected Transferable createTransferable(JComponent component) {
                if (type == MacroBlockType.RANDOM_NUMBER) {
                    return new StringSelection("VALUE:RANDOM_NUMBER:" + randomReporterMinimum + ":" + randomReporterMaximum);
                }
                return new StringSelection("NEW:" + type.name());
            }
        });
        button.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent event) {
                if ((event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
                    button.getTransferHandler().exportAsDrag(button, event, TransferHandler.COPY);
                }
            }
        });
        macroToolboxButtons.add(button);
        return button;
    }


    private void installMacroBlockDragAndDrop() {
        macroBlockList.setDropMode(DropMode.ON_OR_INSERT);
        macroBlockList.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                if (!support.isDrop() || !support.isDataFlavorSupported(DataFlavor.stringFlavor)
                        || macroList.getSelectedValue() == null) return false;
                try {
                    String payload = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                    JList.DropLocation location = (JList.DropLocation) support.getDropLocation();
                    if (payload.startsWith("VALUE:RANDOM_NUMBER:")) {
                        if (location.isInsert()) return false;
                        int index = location.getIndex();
                        Macro macro = macroList.getSelectedValue();
                        if (macro == null || index < 0 || index >= macro.blocks.size()) return false;
                        String[] parts = payload.split(":");
                        if (parts.length != 4) return false;
                        int minimum = Integer.parseInt(parts[2]);
                        int maximum = Integer.parseInt(parts[3]);
                        MacroBlock target = macro.blocks.get(index);
                        return supportsRandomValue(target)
                                && minimum >= 1 && maximum >= minimum;
                    }
                    return payload.startsWith("NEW:") || payload.startsWith("MOVE:");
                } catch (Exception ignored) {
                    return false;
                }
            }

            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    statusLabel.setText("Drop Random onto a numbered [ ] input slot, or drop an action between blocks.");
                    return false;
                }
                try {
                    String payload = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                    JList.DropLocation location = (JList.DropLocation) support.getDropLocation();
                    int index = Math.max(0, location.getIndex());
                    if (payload.startsWith("VALUE:RANDOM_NUMBER:")) {
                        String[] parts = payload.split(":");
                        int minimum = Integer.parseInt(parts[2]);
                        int maximum = Integer.parseInt(parts[3]);
                        Macro macro = macroList.getSelectedValue();
                        if (macro == null || index < 0 || index >= macro.blocks.size()) return false;
                        MacroBlock target = macro.blocks.get(index);
                        int slotMaximum = maximumRandomValueFor(target);
                        if (maximum > slotMaximum) {
                            statusLabel.setText("This number slot supports values up to " + slotMaximum
                                    + ". Click Random in the toolbox and lower its maximum first.");
                            return false;
                        }
                        target.randomMinimum = minimum;
                        target.randomMaximum = maximum;
                        refreshMacroBlocks();
                        macroBlockList.setSelectedIndex(index);
                        persistBinds();
                        statusLabel.setText("Random " + minimum + "–" + maximum + " placed in the "
                                + target.type.label + " number slot.");
                        return true;
                    }
                    if (payload.startsWith("NEW:")) {
                        MacroBlockType type = MacroBlockType.valueOf(payload.substring(4));
                        if (!location.isInsert()) index++;
                        insertMacroBlock(type, index);
                        return true;
                    }
                    if (payload.startsWith("MOVE:")) {
                        Macro macro = macroList.getSelectedValue();
                        if (macro == null) return false;
                        int from = Integer.parseInt(payload.substring(5));
                        if (from < 0 || from >= macro.blocks.size()) return false;
                        int destination = location.isInsert() ? index : index + 1;
                        if (destination > from) destination--;
                        destination = Math.max(0, Math.min(destination, macro.blocks.size() - 1));
                        if (destination == from) return true;
                        synchronized (macro.blocks) {
                            MacroBlock moving = macro.blocks.remove(from);
                            macro.blocks.add(destination, moving);
                        }
                        refreshMacroBlocks();
                        macroBlockList.setSelectedIndex(destination);
                        persistBinds();
                        return true;
                    }
                } catch (Exception exception) {
                    statusLabel.setText("Could not place block: " + exception.getMessage());
                }
                return false;
            }

            @Override protected Transferable createTransferable(JComponent component) {
                int index = macroBlockList.getSelectedIndex();
                return index < 0 ? null : new StringSelection("MOVE:" + index);
            }

            @Override public int getSourceActions(JComponent component) { return MOVE; }
        });
        if (!GraphicsEnvironment.isHeadless()) macroBlockList.setDragEnabled(true);
    }

    private JPanel buildSettingsPanel() {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(0, 12));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(sectionHeading("Settings"));
        JLabel intro = sectionHint("Change how Key Sequence Studio behaves. Your choices are saved automatically.");
        intro.setBorder(new EmptyBorder(4, 1, 8, 0));
        top.add(intro);
        panel.add(top, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel sequenceSettings = sectionHeading("Sequence behavior");
        content.add(sequenceSettings);
        content.add(Box.createVerticalStrut(8));

        JPanel delayRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        delayRow.setOpaque(false);
        JLabel delayLabel = new JLabel("Sequence Delay Mode:");
        delayLabel.setForeground(new Color(45, 55, 69));
        delayLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        delayModeCombo.setPreferredSize(new Dimension(160, 28));
        delayModeCombo.setToolTipText("Applies to Sequence actions only. Hold mode has its own repeat and press timings.");
        delayModeCombo.addActionListener(event -> onDelayModeChanged());
        delayRow.add(delayLabel);
        delayRow.add(delayModeCombo);
        delayRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(delayRow);
        JLabel delayHelp = sectionHint("Per-step uses the timing saved on each action. Fixed modes override key and mouse hold times; explicit Wait steps remain unchanged.");
        delayHelp.setBorder(new EmptyBorder(2, 2, 12, 2));
        content.add(delayHelp);

        JLabel appSettings = sectionHeading("Application behavior");
        content.add(appSettings);
        content.add(Box.createVerticalStrut(8));

        JLabel globalHeading = sectionHeading("Global enable / disable");
        content.add(globalHeading);
        content.add(Box.createVerticalStrut(5));
        JPanel toggleKeyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 2));
        toggleKeyRow.setOpaque(false);
        JLabel toggleKeyCaption = new JLabel("Toggle hotkey:");
        toggleKeyCaption.setForeground(new Color(45, 55, 69));
        toggleKeyCaption.setFont(new Font("Segoe UI", Font.BOLD, 12));
        globalToggleKeyLabel.setForeground(new Color(91, 101, 116));
        globalToggleKeyLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        setGlobalToggleKeyButton.addActionListener(event -> beginGlobalToggleKeyCapture());
        clearGlobalToggleKeyButton.addActionListener(event -> clearGlobalToggleKey());
        toggleKeyRow.add(toggleKeyCaption);
        toggleKeyRow.add(globalToggleKeyLabel);
        toggleKeyRow.add(setGlobalToggleKeyButton);
        toggleKeyRow.add(clearGlobalToggleKeyButton);
        content.add(toggleKeyRow);

        JLabel affectsHint = sectionHint("Choose which groups the hotkey and the manual buttons control. Press the hotkey once to disable the selected groups; press it again to re-enable them. Individual bind enabled/disabled choices are preserved.");
        affectsHint.setBorder(new EmptyBorder(2, 2, 5, 2));
        affectsHint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        content.add(affectsHint);
        JPanel affectsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 1));
        affectsRow.setOpaque(false);
        stylePreferenceCheckBox(globalToggleSequenceCheck);
        stylePreferenceCheckBox(globalToggleHoldCheck);
        stylePreferenceCheckBox(globalToggleMacrosCheck);
        globalToggleSequenceCheck.setSelected(true);
        globalToggleHoldCheck.setSelected(true);
        globalToggleMacrosCheck.setSelected(true);
        globalToggleSequenceCheck.addActionListener(event -> onGlobalToggleGroupsChanged());
        globalToggleHoldCheck.addActionListener(event -> onGlobalToggleGroupsChanged());
        globalToggleMacrosCheck.addActionListener(event -> onGlobalToggleGroupsChanged());
        affectsRow.add(globalToggleSequenceCheck);
        affectsRow.add(globalToggleHoldCheck);
        affectsRow.add(globalToggleMacrosCheck);
        content.add(affectsRow);

        JPanel globalActionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 2));
        globalActionRow.setOpaque(false);
        globalToggleStateLabel.setForeground(new Color(48, 122, 83));
        globalToggleStateLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        disableSelectedGroupsButton.addActionListener(event -> setGlobalToggleDisabled(true, "Selected groups disabled."));
        enableSelectedGroupsButton.addActionListener(event -> setGlobalToggleDisabled(false, "Selected groups enabled."));
        globalActionRow.add(disableSelectedGroupsButton);
        globalActionRow.add(enableSelectedGroupsButton);
        globalActionRow.add(globalToggleStateLabel);
        content.add(globalActionRow);
        JLabel globalRestartHint = sectionHint("This switch resets to enabled when Key Sequence Studio starts. Disabling Hold also releases any currently held generated action. Disabling Sequence or Macros cancels a currently running action in that group.");
        globalRestartHint.setBorder(new EmptyBorder(2, 2, 12, 2));
        globalRestartHint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        content.add(globalRestartHint);

        stylePreferenceCheckBox(confirmDeleteCheck);
        confirmDeleteCheck.setSelected(true);
        confirmDeleteCheck.setToolTipText("Show a confirmation before deleting a Sequence bind, Hold bind, or Macro.");
        confirmDeleteCheck.addActionListener(event -> {
            if (loadingSettings) return;
            persistBinds();
            statusLabel.setText("Delete confirmation " + (confirmDeleteCheck.isSelected() ? "enabled." : "disabled."));
        });
        content.add(confirmDeleteCheck);
        content.add(Box.createVerticalStrut(8));

        stylePreferenceCheckBox(alwaysOnTopCheck);
        alwaysOnTopCheck.addActionListener(event -> {
            if (loadingSettings) return;
            applyAlwaysOnTopPreference();
            persistBinds();
        });
        content.add(alwaysOnTopCheck);
        content.add(Box.createVerticalStrut(14));

        JLabel notificationHeading = sectionHeading("Run notifications");
        content.add(notificationHeading);
        content.add(Box.createVerticalStrut(6));
        stylePreferenceCheckBox(notificationsEnabledCheck);
        notificationsEnabledCheck.setSelected(true);
        notificationsEnabledCheck.setToolTipText("Show a small always-on-top notification when a Sequence bind, Hold bind, or Macro starts.");
        notificationsEnabledCheck.addActionListener(event -> {
            if (loadingSettings) return;
            notificationsEnabled = notificationsEnabledCheck.isSelected();
            persistBinds();
            statusLabel.setText(notificationsEnabled ? "Run notifications enabled." : "Run notifications disabled.");
        });
        content.add(notificationsEnabledCheck);

        JPanel notificationRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 2));
        notificationRow.setOpaque(false);
        JLabel durationLabel = new JLabel("Stay on screen:");
        durationLabel.setForeground(new Color(45, 55, 69));
        durationLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        notificationDurationSpinner.setPreferredSize(new Dimension(84, 27));
        notificationDurationSpinner.addChangeListener(event -> {
            if (loadingSettings) return;
            notificationDurationMs = ((Number) notificationDurationSpinner.getValue()).intValue();
            persistBinds();
        });
        notificationRow.add(durationLabel);
        notificationRow.add(notificationDurationSpinner);
        notificationRow.add(new JLabel("ms"));
        JLabel positionLabel = new JLabel("Position:");
        positionLabel.setForeground(new Color(45, 55, 69));
        positionLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        notificationPositionCombo.setPreferredSize(new Dimension(132, 27));
        notificationPositionCombo.setSelectedItem(notificationPosition);
        notificationPositionCombo.addActionListener(event -> {
            if (loadingSettings) return;
            NotificationPosition selected = (NotificationPosition) notificationPositionCombo.getSelectedItem();
            if (selected != null) {
                notificationPosition = selected;
                positionNotificationWindows();
                persistBinds();
            }
        });
        notificationRow.add(positionLabel);
        notificationRow.add(notificationPositionCombo);
        previewNotificationButton.addActionListener(event -> showNotification("Preview notification", "Small overlay with a visible timer bar."));
        notificationRow.add(previewNotificationButton);
        content.add(notificationRow);
        JLabel notificationHelp = sectionHint("Notifications are compact, stay above normal windows, and show a visible countdown bar. Choose any screen corner. Some exclusive full-screen games may cover desktop overlays.");
        notificationHelp.setBorder(new EmptyBorder(2, 2, 12, 2));
        notificationHelp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        content.add(notificationHelp);

        JLabel note = sectionHint("Bind names are shown as the main label. Their original trigger appears beside the name in gray, so renaming never changes which key or mouse button activates the bind.");
        note.setBorder(new EmptyBorder(4, 2, 0, 2));
        content.add(note);

        content.setBorder(new EmptyBorder(4, 4, 4, 4));
        JScrollPane settingsScroll = new JScrollPane(content);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        settingsScroll.getViewport().setOpaque(false);
        settingsScroll.setOpaque(false);
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(settingsScroll, BorderLayout.CENTER);
        refreshGlobalToggleControls();
        return panel;
    }

    private static void stylePreferenceCheckBox(JCheckBox checkBox) {
        checkBox.setOpaque(false);
        checkBox.setForeground(new Color(45, 55, 69));
        checkBox.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        checkBox.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private void applyAlwaysOnTopPreference() {
        try {
            boolean enabled = alwaysOnTopCheck.isSelected();
            setAlwaysOnTop(enabled);
            statusLabel.setText(enabled ? "Window will stay above other windows." : "Always-on-top disabled.");
        } catch (SecurityException exception) {
            alwaysOnTopCheck.setSelected(false);
            statusLabel.setText("Windows did not allow the always-on-top setting.");
        }
    }

    private JPanel buildSequenceModePanel() {
        JPanel main = new JPanel(new GridLayout(1, 2, 10, 0));
        main.setOpaque(false);
        main.add(buildBindsPanel());
        main.add(buildSequencePanel());
        return main;
    }

    private JPanel buildHoldModePanel() {
        JPanel main = new JPanel(new GridLayout(1, 2, 10, 0));
        main.setOpaque(false);
        main.add(buildHoldBindsPanel());
        main.add(buildHoldSettingsPanel());
        return main;
    }

    /**
     * Reversible Fortnite graphics presets. The config location is detected automatically;
     * the app only edits documented/commonly exposed user settings and never game assets.
     */
    private JPanel buildPotatoModePanel() {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(0, 12));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(sectionHeading("Potato Mode"));
        JLabel intro = sectionHint("Choose a built-in Fortnite graphics preset. The config file is detected automatically and backed up before changes.");
        intro.setBorder(new EmptyBorder(4, 1, 8, 0));
        top.add(intro);

        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        presetRow.setOpaque(false);
        JLabel presetLabel = new JLabel("Preset:");
        presetLabel.setForeground(new Color(45, 55, 69));
        presetLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        potatoPresetCombo.setPreferredSize(new Dimension(250, 29));
        potatoPresetCombo.addActionListener(event -> {
            updatePotatoPresetStorage();
            updatePotatoPresetDescription();
            persistBinds();
        });
        presetRow.add(presetLabel);
        presetRow.add(potatoPresetCombo);
        top.add(presetRow);
        panel.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        JPanel presetCard = new JPanel(new BorderLayout(8, 8));
        presetCard.setBackground(Color.WHITE);
        presetCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(215, 220, 228)),
                new EmptyBorder(16, 16, 16, 16)));
        JLabel selected = new JLabel("Selected preset details");
        selected.setForeground(new Color(35, 45, 60));
        selected.setFont(new Font("Segoe UI", Font.BOLD, 15));
        potatoPresetDescription.setForeground(new Color(75, 86, 102));
        potatoPresetDescription.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        potatoPresetDescription.setBorder(new EmptyBorder(4, 0, 0, 0));
        presetCard.add(selected, BorderLayout.NORTH);
        presetCard.add(potatoPresetDescription, BorderLayout.CENTER);
        center.add(presetCard);
        center.add(Box.createVerticalStrut(12));

        JLabel details = sectionHint("Preset changes include lower scalability groups, reduced effects/shadows, disabled motion blur and VSync, and reduced rendering resolution for the stronger presets.");
        details.setBorder(new EmptyBorder(2, 2, 8, 2));
        center.add(details);
        JLabel pathInfo = sectionHint("Auto-detected file: %LOCALAPPDATA%\\FortniteGame\\Saved\\Config\\WindowsClient\\GameUserSettings.ini");
        pathInfo.setBorder(new EmptyBorder(2, 2, 8, 2));
        center.add(pathInfo);
        panel.add(center, BorderLayout.CENTER);

        JButton restore = secondaryButton("Restore previous settings");
        restore.addActionListener(event -> restorePotatoConfigBackup());
        JButton apply = primaryButton("Apply selected preset");
        apply.addActionListener(event -> applyPotatoMode());
        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        actions.setOpaque(false);
        actions.add(restore);
        actions.add(apply);

        JLabel warning = sectionHint("Close Fortnite before applying. Rendering Mode = Performance must still be selected in Fortnite's Video settings, then the game must be restarted. This app does not delete textures or modify game binaries.");
        warning.setForeground(new Color(145, 83, 36));
        warning.setBorder(new EmptyBorder(8, 2, 0, 2));
        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.add(actions);
        footer.add(warning);
        panel.add(footer, BorderLayout.SOUTH);

        if (potatoPresetCombo.getSelectedItem() == null) potatoPresetCombo.setSelectedItem(PotatoPreset.FORTNITE_LOW);
        updatePotatoPresetStorage();
        updatePotatoPresetDescription();
        return panel;
    }

    private void updatePotatoPresetStorage() {
        PotatoPreset preset = (PotatoPreset) potatoPresetCombo.getSelectedItem();
        if (preset == null) preset = PotatoPreset.FORTNITE_LOW;
        potatoGameNameField.setText(preset.label);
        potatoConfigPathField.setText(fortniteConfigPath().toString());
        potatoOverridesArea.setText(preset.iniOverrides());
    }

    private void updatePotatoPresetDescription() {
        PotatoPreset preset = (PotatoPreset) potatoPresetCombo.getSelectedItem();
        potatoPresetDescription.setText(preset == null ? "Choose a preset." : preset.description);
    }

    private void selectSavedPotatoPreset(String savedName) {
        PotatoPreset match = null;
        for (PotatoPreset preset : PotatoPreset.values()) {
            if (preset.label.equalsIgnoreCase(savedName)) {
                match = preset;
                break;
            }
        }
        // Older version-8 files used a free-form game name; migrate those to the
        // recommended Fortnite preset rather than restoring an obsolete file-picker UI.
        potatoPresetCombo.setSelectedItem(match == null ? PotatoPreset.FORTNITE_LOW : match);
        updatePotatoPresetStorage();
        updatePotatoPresetDescription();
    }

    private static Path fortniteConfigPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData == null || localAppData.isBlank())
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(localAppData);
        return base.resolve("FortniteGame").resolve("Saved").resolve("Config")
                .resolve("WindowsClient").resolve("GameUserSettings.ini");
    }

    private void applyPotatoMode() {
        PotatoPreset preset = (PotatoPreset) potatoPresetCombo.getSelectedItem();
        if (preset == null) preset = PotatoPreset.FORTNITE_LOW;
        Path target = fortniteConfigPath().toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(target)) {
                throw new IOException("Fortnite's settings file was not found. Open Fortnite once, close it, then apply the preset again.");
            }
            if (!Files.isWritable(target)) {
                throw new IOException("The Fortnite settings file is not writable. Check that it is not read-only and close Fortnite first.");
            }
            if (Files.size(target) > 5L * 1024L * 1024L) {
                throw new IOException("The settings file is larger than 5 MB; refusing to edit it.");
            }
            Map<String, LinkedHashMap<String, IniSetting>> parsed = parseIniOverrides(preset.iniOverrides());
            Path backup = potatoBackupPath(target);
            int answer = JOptionPane.showConfirmDialog(this,
                    "Apply '" + preset.label + "' to Fortnite?\n\n"
                            + "Settings file: " + target + "\n\n"
                            + "A backup will be saved as:\n" + backup + "\n\n"
                            + "Fortnite must be closed. Existing original backup will be kept. Continue?",
                    "Apply Fortnite preset", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;

            String original = Files.readString(target, StandardCharsets.UTF_8);
            String updated = mergeIniOverrides(original, parsed);
            if (!Files.exists(backup)) Files.copy(target, backup);
            Path temporary = target.resolveSibling(target.getFileName() + ".kqs-tmp");
            Files.writeString(temporary, updated, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            updatePotatoPresetStorage();
            persistBinds();
            statusLabel.setText("Applied " + preset.label + ". Set Rendering Mode to Performance in Fortnite and restart the game.");
            JOptionPane.showMessageDialog(this,
                    "Preset applied.\n\nFor Fortnite's full Performance Mode, open Fortnite's Video settings, select Rendering Mode → Performance, apply, and restart the game.",
                    "Fortnite preset applied", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception exception) {
            statusLabel.setText("Potato preset could not be applied: " + exception.getMessage());
            JOptionPane.showMessageDialog(this, "Could not apply the Fortnite preset:\n" + exception.getMessage(),
                    "Potato Mode", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restorePotatoConfigBackup() {
        Path target = fortniteConfigPath().toAbsolutePath().normalize();
        try {
            Path backup = potatoBackupPath(target);
            if (!Files.isRegularFile(backup)) {
                statusLabel.setText("No Key Sequence Studio backup was found for Fortnite settings.");
                return;
            }
            int answer = JOptionPane.showConfirmDialog(this,
                    "Restore the saved original Fortnite settings?\n\n" + target,
                    "Restore Fortnite settings", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(backup);
            statusLabel.setText("Restored Fortnite's original settings backup.");
        } catch (Exception exception) {
            statusLabel.setText("Could not restore Fortnite settings: " + exception.getMessage());
            JOptionPane.showMessageDialog(this, "Could not restore Fortnite settings:\n" + exception.getMessage(),
                    "Potato Mode", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Path potatoBackupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".keysequencestudio-backup");
    }

    private static Map<String, LinkedHashMap<String, IniSetting>> parseIniOverrides(String text) throws IOException {
        Map<String, LinkedHashMap<String, IniSetting>> result = new LinkedHashMap<>();
        String currentSection = "";
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
                currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                if (currentSection.isEmpty()) throw new IOException("Section name is empty on line " + (i + 1) + ".");
                continue;
            }
            if (currentSection.isEmpty()) {
                throw new IOException("Add a [Section] header before settings (line " + (i + 1) + ").");
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) throw new IOException("Invalid setting on line " + (i + 1) + ". Use Setting=Value inside a [Section].");
            String key = trimmed.substring(0, equals).trim();
            String value = trimmed.substring(equals + 1).trim();
            if (key.isEmpty()) throw new IOException("A setting name is missing on line " + (i + 1) + ".");
            String sectionKey = currentSection.toLowerCase(Locale.ROOT);
            String keyKey = key.toLowerCase(Locale.ROOT);
            result.computeIfAbsent(sectionKey, ignored -> new LinkedHashMap<>())
                    .put(keyKey, new IniSetting(currentSection, key, value));
        }
        return result;
    }

    private static String mergeIniOverrides(String original, Map<String, LinkedHashMap<String, IniSetting>> overrides) {
        List<String> sourceLines = new ArrayList<>(List.of(original.split("\\R", -1)));
        List<String> output = new ArrayList<>();
        Map<String, Set<String>> applied = new HashMap<>();
        Set<String> sectionsSeen = new HashSet<>();
        String currentSection = "";

        for (String line : sourceLines) {
            String trimmed = line.trim();
            // Some Windows INI files start with a UTF-8 BOM before the first section.
            if (trimmed.startsWith("\uFEFF")) trimmed = trimmed.substring(1).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
                appendMissingIniSettings(output, currentSection, overrides, applied);
                currentSection = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase(Locale.ROOT);
                sectionsSeen.add(currentSection);
                output.add(line);
                continue;
            }
            int equals = line.indexOf('=');
            if (equals > 0) {
                String key = line.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                IniSetting setting = overrides.getOrDefault(currentSection, new LinkedHashMap<>()).get(key);
                if (setting != null) {
                    output.add(line.substring(0, equals + 1) + setting.value);
                    applied.computeIfAbsent(currentSection, ignored -> new HashSet<>()).add(key);
                    continue;
                }
            }
            output.add(line);
        }
        appendMissingIniSettings(output, currentSection, overrides, applied);

        for (Map.Entry<String, LinkedHashMap<String, IniSetting>> sectionEntry : overrides.entrySet()) {
            String sectionKey = sectionEntry.getKey();
            if (sectionsSeen.contains(sectionKey)) continue;
            if (!output.isEmpty() && !output.get(output.size() - 1).isBlank()) output.add("");
            IniSetting first = sectionEntry.getValue().values().stream().findFirst().orElse(null);
            if (first != null) output.add("[" + first.sectionName + "]");
            appendMissingIniSettings(output, sectionKey, overrides, applied);
        }
        return String.join(System.lineSeparator(), output);
    }

    private static void appendMissingIniSettings(List<String> output, String section,
                                                  Map<String, LinkedHashMap<String, IniSetting>> overrides,
                                                  Map<String, Set<String>> applied) {
        LinkedHashMap<String, IniSetting> settings = overrides.get(section);
        if (settings == null) return;
        Set<String> done = applied.computeIfAbsent(section, ignored -> new HashSet<>());
        for (Map.Entry<String, IniSetting> entry : settings.entrySet()) {
            if (done.add(entry.getKey())) output.add(entry.getValue().key + "=" + entry.getValue().value);
        }
    }

    private JPanel buildHoldBindsPanel() {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(0, 10));
        panel.add(sectionHeading("Hold binds"), BorderLayout.NORTH);

        styleList(holdBindList);
        holdBindList.setFixedCellHeight(46);
        holdBindList.setCellRenderer(new HoldBindRenderer());
        holdBindList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) refreshHoldControls();
        });
        JScrollPane scroll = new JScrollPane(holdBindList);
        styleScrollPane(scroll);
        panel.add(scroll, BorderLayout.CENTER);

        JButton add = primaryButton("Add hold bind");
        add.setToolTipText("Press the key or mouse button you want to hold as the trigger");
        add.addActionListener(event -> beginHoldTriggerCapture());
        JButton rename = secondaryButton("Rename");
        rename.addActionListener(event -> renameSelectedHoldBind());
        JButton remove = secondaryButton("Remove bind");
        remove.addActionListener(event -> removeSelectedHoldBind());
        JButton toggle = secondaryButton("Enable / disable");
        toggle.addActionListener(event -> toggleSelectedHoldBind());

        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 6));
        buttons.setOpaque(false);
        buttons.add(add);
        JPanel lower = new JPanel(new GridLayout(1, 3, 6, 0));
        lower.setOpaque(false);
        lower.add(rename);
        lower.add(remove);
        lower.add(toggle);
        buttons.add(lower);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildHoldSettingsPanel() {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(0, 10));
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel heading = sectionHeading("Hold settings");
        top.add(heading);
        JLabel description = sectionHint("Repeats one action while the trigger is physically held.");
        description.setBorder(new EmptyBorder(4, 1, 0, 0));
        top.add(description);
        panel.add(top, BorderLayout.NORTH);

        JPanel fields = new JPanel();
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        holdActionLabel.setForeground(new Color(45, 55, 69));
        holdActionLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        holdActionLabel.setBorder(new EmptyBorder(2, 0, 8, 0));
        fields.add(holdActionLabel);
        setHoldActionButton.addActionListener(event -> beginHoldActionCapture());
        setHoldActionButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.add(setHoldActionButton);
        fields.add(Box.createVerticalStrut(14));

        fields.add(labeledSpinner("Repeat every", holdIntervalSpinner, "ms"));
        fields.add(Box.createVerticalStrut(8));
        fields.add(labeledSpinner("Press duration", holdPressSpinner, "ms"));
        fields.add(Box.createVerticalStrut(8));
        JLabel hint = sectionHint("The repeated action can be the same key or mouse button as the trigger. Releasing the physical trigger stops repeats and releases any active output.");
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        fields.add(hint);
        fields.add(Box.createVerticalStrut(12));
        JLabel rules = sectionHint("Use repeated inputs only in apps that permit automation. Some games restrict macros.");
        rules.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        fields.add(rules);
        panel.add(fields, BorderLayout.CENTER);

        holdIntervalSpinner.addChangeListener(event -> updateSelectedHoldTiming());
        holdPressSpinner.addChangeListener(event -> updateSelectedHoldTiming());
        refreshHoldControls();
        return panel;
    }

    private JPanel labeledSpinner(String label, JSpinner spinner, String suffix) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 2));
        row.setOpaque(false);
        JLabel name = new JLabel(label + ":");
        name.setForeground(new Color(45, 55, 69));
        name.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        spinner.setPreferredSize(new Dimension(96, 27));
        spinner.setEnabled(false);
        row.add(name);
        row.add(spinner);
        row.add(new JLabel(suffix));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private JPanel buildBindsPanel() {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(0, 10));
        panel.add(sectionHeading("Binds"), BorderLayout.NORTH);

        styleList(bindList);
        bindList.setFixedCellHeight(42);
        bindList.setCellRenderer(new BindRenderer());
        bindList.addListSelectionListener(this::onBindSelectionChanged);
        JScrollPane scroll = new JScrollPane(bindList);
        styleScrollPane(scroll);
        panel.add(scroll, BorderLayout.CENTER);

        JButton add = primaryButton("Add bind");
        add.addActionListener(event -> beginTriggerCapture());
        JButton addTrigger = secondaryButton("Add trigger to selected");
        addTrigger.addActionListener(event -> beginAdditionalSequenceTriggerCapture());
        JButton rename = secondaryButton("Rename");
        rename.addActionListener(event -> renameSelectedBind());
        JButton remove = secondaryButton("Remove bind");
        remove.addActionListener(event -> removeSelectedBind());
        JButton toggle = secondaryButton("Enable / disable");
        toggle.addActionListener(event -> toggleSelectedBind());

        JPanel buttons = new JPanel(new GridLayout(3, 1, 0, 6));
        buttons.setOpaque(false);
        buttons.add(add);
        buttons.add(addTrigger);
        JPanel lower = new JPanel(new GridLayout(1, 3, 6, 0));
        lower.setOpaque(false);
        lower.add(rename);
        lower.add(remove);
        lower.add(toggle);
        buttons.add(lower);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildSequencePanel() {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(0, 10));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        sequenceHeading.setText("Sequence");
        sequenceHeading.setForeground(new Color(32, 38, 48));
        sequenceHeading.setFont(new Font("Segoe UI", Font.BOLD, 15));
        top.add(sequenceHeading);
        JLabel explanation = new JLabel("Add a key or mouse click, then set its timing.");
        explanation.setForeground(new Color(91, 101, 116));
        explanation.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        explanation.setBorder(new EmptyBorder(4, 1, 0, 0));
        top.add(explanation);

        JPanel repeatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        repeatRow.setOpaque(false);
        JLabel repeatLabel = new JLabel("Repeat #");
        repeatLabel.setForeground(new Color(45, 55, 69));
        repeatLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        repeatSpinner.setPreferredSize(new Dimension(78, 27));
        repeatSpinner.setEnabled(false);
        repeatSpinner.setToolTipText("How many times to run the complete sequence for this bind");
        repeatSpinner.addChangeListener(event -> onRepeatCountChanged());
        repeatRow.add(repeatLabel);
        repeatRow.add(repeatSpinner);
        JLabel repeatHint = new JLabel("times per trigger");
        repeatHint.setForeground(new Color(91, 101, 116));
        repeatHint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        repeatRow.add(repeatHint);
        top.add(repeatRow);
        panel.add(top, BorderLayout.NORTH);

        styleList(stepList);
        stepList.setFixedCellHeight(38);
        stepList.setCellRenderer(new StepRenderer());
        JScrollPane scroll = new JScrollPane(stepList);
        styleScrollPane(scroll);
        panel.add(scroll, BorderLayout.CENTER);

        JButton addAction = primaryButton("Add action");
        addAction.setToolTipText("Click here, then press a key or click a mouse button");
        addAction.addActionListener(event -> beginSequenceActionCapture());
        JButton addWait = secondaryButton("Add wait");
        addWait.addActionListener(event -> addWaitStep());
        JButton editMs = secondaryButton("Edit ms");
        editMs.addActionListener(event -> editSelectedTiming());
        JButton setAllMs = secondaryButton("Set ms for all");
        setAllMs.setToolTipText("Set the same hold duration for every key and mouse action in this bind");
        setAllMs.addActionListener(event -> setMillisecondsForAllActions());
        JButton remove = secondaryButton("Remove step");
        remove.addActionListener(event -> removeSelectedStep());
        JButton up = secondaryButton("Move up");
        up.addActionListener(event -> moveSelectedStep(-1));
        JButton down = secondaryButton("Move down");
        down.addActionListener(event -> moveSelectedStep(1));

        JPanel row1 = new JPanel(new GridLayout(1, 2, 6, 0));
        row1.setOpaque(false);
        row1.add(addAction);
        row1.add(addWait);
        JPanel row2 = new JPanel(new GridLayout(1, 2, 6, 0));
        row2.setOpaque(false);
        row2.add(editMs);
        row2.add(setAllMs);
        JPanel row3 = new JPanel(new GridLayout(1, 2, 6, 0));
        row3.setOpaque(false);
        row3.add(remove);
        row3.add(up);
        JPanel row4 = new JPanel(new GridLayout(1, 1, 6, 0));
        row4.setOpaque(false);
        row4.add(down);
        JPanel buttons = new JPanel(new GridLayout(4, 1, 0, 6));
        buttons.setOpaque(false);
        buttons.add(row1);
        buttons.add(row2);
        buttons.add(row3);
        buttons.add(row4);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel cardPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(205, 211, 220)),
                new EmptyBorder(12, 12, 12, 12)));
        return panel;
    }

    private JLabel sectionHeading(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(32, 38, 48));
        label.setFont(new Font("Segoe UI", Font.BOLD, 15));
        return label;
    }

    private JLabel sectionHint(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(91, 101, 116));
        label.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        return label;
    }

    private void styleList(JList<?> list) {
        list.setBackground(Color.WHITE);
        list.setForeground(new Color(32, 38, 48));
        list.setSelectionBackground(new Color(218, 232, 250));
        list.setSelectionForeground(new Color(25, 43, 66));
        list.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        list.setBorder(new EmptyBorder(4, 4, 4, 4));
        list.setFocusable(true);
    }

    private void styleScrollPane(JScrollPane pane) {
        pane.setBorder(BorderFactory.createLineBorder(new Color(215, 220, 228)));
        pane.getViewport().setBackground(Color.WHITE);
        pane.getVerticalScrollBar().setUnitIncrement(16);
    }

    private JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        button.setFocusPainted(true);
        return button;
    }

    private JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        button.setFocusPainted(true);
        return button;
    }

    private void installGlobalKeyListener() {
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.OFF);
        logger.setUseParentHandlers(false);
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            GlobalScreen.addNativeMouseListener(this);
            // Wheel rotation is a different native event from a physical button press.
            // Listen only so capture mode can explain that scrolling is intentionally ignored.
            GlobalScreen.addNativeMouseWheelListener(this);
        } catch (NativeHookException exception) {
            throw new IllegalStateException(
                    "Could not start the global keyboard/mouse listener. Try closing other apps that use global keyboard hooks, then restart.\n" + exception.getMessage(),
                    exception);
        }
    }

    private void beginGlobalToggleKeyCapture() {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) {
            statusLabel.setText("Finish the current key capture before setting the global toggle hotkey.");
            return;
        }
        capturingGlobalToggleKey = true;
        settingsTabButton.setSelected(true);
        modeCardLayout.show(modeCards, "settings");
        statusLabel.setText("GLOBAL TOGGLE HOTKEY  ·  press a keyboard key or mouse button");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void captureGlobalToggleKey(int triggerCode) {
        if (!capturingGlobalToggleKey) return;
        if (triggerIsAssigned(triggerCode)) {
            statusLabel.setText("That trigger is already assigned. Remove or change its existing bind before using it as the global toggle.");
            return;
        }
        if (isAutomationOutputCodeAssigned(triggerCode)) {
            statusLabel.setText("That key or mouse button is already used as an output action. Choose another toggle trigger to avoid self-triggering.");
            return;
        }
        capturingGlobalToggleKey = false;
        globalToggleTriggerCode = triggerCode;
        setCursor(Cursor.getDefaultCursor());
        refreshGlobalToggleControls();
        persistBinds();
        statusLabel.setText("Global enable/disable hotkey set to " + triggerName(triggerCode) + ".");
    }

    private void clearGlobalToggleKey() {
        capturingGlobalToggleKey = false;
        globalToggleTriggerCode = 0;
        setCursor(Cursor.getDefaultCursor());
        refreshGlobalToggleControls();
        persistBinds();
        statusLabel.setText("Global enable/disable hotkey cleared.");
    }

    private void onGlobalToggleGroupsChanged() {
        if (loadingSettings) return;
        globalToggleAffectsSequence = globalToggleSequenceCheck.isSelected();
        globalToggleAffectsHold = globalToggleHoldCheck.isSelected();
        globalToggleAffectsMacros = globalToggleMacrosCheck.isSelected();
        if (!hasGlobalToggleGroupsSelected()) globalToggleDisabled = false;
        applyGlobalToggleEffects();
        refreshGlobalToggleControls();
        persistBinds();
    }

    private boolean hasGlobalToggleGroupsSelected() {
        return globalToggleAffectsSequence || globalToggleAffectsHold || globalToggleAffectsMacros;
    }

    private boolean isSequenceRuntimeEnabled() {
        return !(globalToggleDisabled && globalToggleAffectsSequence);
    }

    private boolean isHoldRuntimeEnabled() {
        return !(globalToggleDisabled && globalToggleAffectsHold);
    }

    private boolean isMacrosRuntimeEnabled() {
        return !(globalToggleDisabled && globalToggleAffectsMacros);
    }

    private void toggleGlobalGroupsFromHotkey() {
        if (!hasGlobalToggleGroupsSelected()) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("Select at least one group for the global toggle in Settings."));
            return;
        }
        setGlobalToggleDisabled(!globalToggleDisabled,
                globalToggleDisabled ? "Selected groups enabled by hotkey." : "Selected groups disabled by hotkey.");
    }

    private void setGlobalToggleDisabled(boolean disabled, String status) {
        if (!hasGlobalToggleGroupsSelected()) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("Select at least one group for the global toggle in Settings."));
            return;
        }
        globalToggleDisabled = disabled;
        applyGlobalToggleEffects();
        refreshGlobalToggleControls();
        SwingUtilities.invokeLater(() -> {
            if (!closing) statusLabel.setText(status);
        });
    }

    private void applyGlobalToggleEffects() {
        if (!isHoldRuntimeEnabled()) {
            stopAllHoldBinds();
        } else {
            // If Hold is re-enabled while a physical trigger is still down, resume that bind.
            for (HoldBind bind : holdTriggerMap.values()) {
                boolean down;
                synchronized (physicallyDown) {
                    down = physicallyDown.contains(bind.triggerKeyCode);
                }
                if (down && bind.enabled) startHoldBind(bind);
            }
        }

        AutomationCategory running = activeAutomationCategory;
        boolean shouldCancel = (running == AutomationCategory.SEQUENCE && !isSequenceRuntimeEnabled())
                || (running == AutomationCategory.MACRO && !isMacrosRuntimeEnabled());
        if (shouldCancel) {
            Future<?> active = activeAutomationFuture;
            if (active != null) active.cancel(true);
        }
    }

    private void refreshGlobalToggleControls() {
        Runnable update = () -> {
            globalToggleKeyLabel.setText(globalToggleTriggerCode == 0 ? "Not set" : "[" + triggerName(globalToggleTriggerCode) + "]");
            boolean disabled = globalToggleDisabled && hasGlobalToggleGroupsSelected();
            globalToggleStateLabel.setText(disabled ? "Currently disabled" : "Currently enabled");
            globalToggleStateLabel.setForeground(disabled ? new Color(170, 70, 55) : new Color(48, 122, 83));
        };
        if (SwingUtilities.isEventDispatchThread()) update.run();
        else SwingUtilities.invokeLater(update);
    }

    private boolean triggerIsAssigned(int triggerCode) {
        return triggerMap.containsKey(triggerCode) || holdTriggerMap.containsKey(triggerCode) || macroTriggerMap.containsKey(triggerCode);
    }

    private boolean isAutomationOutputCodeAssigned(int triggerCode) {
        for (int i = 0; i < bindListModel.size(); i++) {
            Bind bind = bindListModel.get(i);
            synchronized (bind.steps) {
                for (Step step : bind.steps) {
                    if (step.type == StepType.KEY && triggerCode == step.keyCode) return true;
                    if (step.type == StepType.MOUSE && triggerCode == -step.mouseButton) return true;
                }
            }
        }
        for (HoldBind bind : holdTriggerMap.values()) {
            if (bind.actionType == HoldActionType.KEY && triggerCode == bind.actionCode) return true;
            if (bind.actionType == HoldActionType.MOUSE && triggerCode == -bind.actionCode) return true;
        }
        for (int i = 0; i < macroListModel.size(); i++) {
            Macro macro = macroListModel.get(i);
            synchronized (macro.blocks) {
                for (MacroBlock block : macro.blocks) {
                    if (block.type == MacroBlockType.KEY && triggerCode == block.code) return true;
                    if (block.type == MacroBlockType.MOUSE && triggerCode == -block.code) return true;
                }
            }
        }
        return false;
    }

    private void beginTriggerCapture() {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) return;
        waitingForTrigger = true;
        statusLabel.setText("ADD BIND  ·  press a keyboard key or mouse button (scroll wheel is ignored; Mouse 4/5 are side buttons)");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void beginSequenceActionCapture() {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) return;
        if (bindList.getSelectedValue() == null) {
            statusLabel.setText("Select a bind first, then add an action to its sequence.");
            return;
        }
        capturingSequenceAction = true;
        statusLabel.setText("ADD ACTION  ·  press a key or click a mouse button (scroll wheel is ignored)");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void beginHoldTriggerCapture() {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) return;
        waitingForHoldTrigger = true;
        holdTabButton.setSelected(true);
        modeCardLayout.show(modeCards, "hold");
        statusLabel.setText("ADD HOLD BIND  ·  press the key or mouse button to hold as the trigger");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void beginAdditionalSequenceTriggerCapture() {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) return;
        Bind bind = bindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Select a Sequence bind first.");
            return;
        }
        if (bind.triggerCodes.size() >= MAX_TRIGGERS_PER_BIND) {
            statusLabel.setText("This bind already has the maximum number of triggers.");
            return;
        }
        pendingAdditionalSequenceTriggerBind = bind;
        capturingAdditionalSequenceTrigger = true;
        sequenceTabButton.setSelected(true);
        modeCardLayout.show(modeCards, "sequence");
        statusLabel.setText("ADD TRIGGER  ·  press another key or mouse button for this bind");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void beginNewMacroTriggerCapture() {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) return;
        pendingMacroTriggerTarget = null;
        creatingMacroFromTrigger = true;
        capturingMacroTrigger = true;
        macrosTabButton.setSelected(true);
        modeCardLayout.show(modeCards, "macros");
        statusLabel.setText("ADD MACRO  ·  press its first trigger key or mouse button");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void beginAdditionalMacroTriggerCapture() {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) return;
        Macro macro = macroList.getSelectedValue();
        if (macro == null) {
            statusLabel.setText("Select a macro first.");
            return;
        }
        if (macro.triggerCodes.size() >= MAX_TRIGGERS_PER_BIND) {
            statusLabel.setText("This macro already has the maximum number of triggers.");
            return;
        }
        pendingMacroTriggerTarget = macro;
        creatingMacroFromTrigger = false;
        capturingMacroTrigger = true;
        macrosTabButton.setSelected(true);
        modeCardLayout.show(modeCards, "macros");
        statusLabel.setText("ADD MACRO TRIGGER  ·  press another key or mouse button");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void addCapturedMacroTrigger(int triggerCode) {
        if (!capturingMacroTrigger) return;
        capturingMacroTrigger = false;
        setCursor(Cursor.getDefaultCursor());
        if (triggerCode == globalToggleTriggerCode || triggerMap.containsKey(triggerCode) || holdTriggerMap.containsKey(triggerCode) || macroTriggerMap.containsKey(triggerCode)) {
            statusLabel.setText(triggerCode == globalToggleTriggerCode
                    ? "That trigger is reserved for the global enable/disable hotkey."
                    : "That trigger is already assigned. Choose a different key or mouse button.");
            pendingMacroTriggerTarget = null;
            creatingMacroFromTrigger = false;
            return;
        }
        Macro macro = pendingMacroTriggerTarget;
        if (creatingMacroFromTrigger || macro == null) {
            if (macroListModel.size() >= MAX_BINDS) {
                creatingMacroFromTrigger = false;
                pendingMacroTriggerTarget = null;
                statusLabel.setText("The macro limit has been reached.");
                return;
            }
            macro = new Macro("Macro " + (macroListModel.size() + 1), triggerCode);
            macroListModel.addElement(macro);
            macroList.setSelectedValue(macro, true);
        } else {
            macro.triggerCodes.add(triggerCode);
        }
        macroTriggerMap.put(triggerCode, macro);
        pendingMacroTriggerTarget = null;
        creatingMacroFromTrigger = false;
        macroList.repaint();
        refreshMacroBlocks();
        persistBinds();
        statusLabel.setText("Macro trigger set to " + triggerName(triggerCode) + ". Add blocks to its stack.");
    }

    private void addAdditionalSequenceTrigger(int triggerCode) {
        if (!capturingAdditionalSequenceTrigger) return;
        capturingAdditionalSequenceTrigger = false;
        setCursor(Cursor.getDefaultCursor());
        Bind bind = pendingAdditionalSequenceTriggerBind;
        pendingAdditionalSequenceTriggerBind = null;
        if (bind == null || !containsBind(bindListModel, bind)) return;
        if (triggerCode == globalToggleTriggerCode || triggerMap.containsKey(triggerCode) || holdTriggerMap.containsKey(triggerCode) || macroTriggerMap.containsKey(triggerCode)) {
            statusLabel.setText(triggerCode == globalToggleTriggerCode
                    ? "That trigger is reserved for the global enable/disable hotkey."
                    : "That trigger is already assigned. Choose a different key or mouse button.");
            return;
        }
        bind.triggerCodes.add(triggerCode);
        triggerMap.put(triggerCode, bind);
        bindList.repaint();
        refreshSteps();
        persistBinds();
        statusLabel.setText("Added " + triggerName(triggerCode) + " to " + bindDisplayName(bind.displayName, bind.triggerCodes) + ".");
    }

    private static boolean containsBind(DefaultListModel<Bind> model, Bind target) {
        for (int i = 0; i < model.size(); i++) if (model.get(i) == target) return true;
        return false;
    }

    private void beginHoldActionCapture() {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) return;
        HoldBind bind = holdBindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Select a hold bind first.");
            return;
        }
        stopHoldBind(bind);
        capturingHoldAction = true;
        statusLabel.setText("SET HOLD ACTION  ·  press a key or click a mouse button to repeat");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void addHoldBind(int triggerCode) {
        if (!waitingForHoldTrigger) return;
        waitingForHoldTrigger = false;
        setCursor(Cursor.getDefaultCursor());
        if (triggerCode == globalToggleTriggerCode || triggerMap.containsKey(triggerCode) || holdTriggerMap.containsKey(triggerCode) || macroTriggerMap.containsKey(triggerCode)) {
            statusLabel.setText(triggerCode == globalToggleTriggerCode
                    ? "That trigger is reserved for the global enable/disable hotkey."
                    : "That trigger is already used. Choose a different trigger.");
            return;
        }
        HoldBind bind = new HoldBind(triggerCode);
        holdBindListModel.addElement(bind);
        holdTriggerMap.put(triggerCode, bind);
        holdBindList.setSelectedIndex(holdBindListModel.size() - 1);
        persistBinds();
        statusLabel.setText("Created hold bind for " + triggerName(triggerCode) + ". Set the repeated action on the right.");
    }

    private void addCapturedHoldAction(int actionCode) {
        if (!capturingHoldAction) return;
        capturingHoldAction = false;
        setCursor(Cursor.getDefaultCursor());
        HoldBind bind = holdBindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Select a hold bind first.");
            return;
        }
        if (actionCode == globalToggleTriggerCode) {
            statusLabel.setText("That input is reserved for the global enable/disable hotkey. Choose another repeated action.");
            return;
        }
        // Matching the trigger is allowed (e.g. hold E -> repeat E). The global
        // listeners below filter the short synthetic key-up/button-up events generated
        // by Robot so they do not look like the user's physical trigger release.
        if (actionCode < 0) {
            int button = -actionCode;
            if (!isSupportedMouseButton(button)) {
                statusLabel.setText("That mouse button is not supported.");
                return;
            }
            bind.actionType = HoldActionType.MOUSE;
            bind.actionCode = button;
        } else {
            bind.actionType = HoldActionType.KEY;
            bind.actionCode = actionCode;
        }
        refreshHoldControls();
        holdBindList.repaint();
        persistBinds();
        synchronized (physicallyDown) {
            if (physicallyDown.contains(bind.triggerKeyCode)) startHoldBind(bind);
        }
        statusLabel.setText("Hold action set to " + holdActionName(bind) + ". It repeats while "
                + triggerName(bind.triggerKeyCode) + " is held.");
    }

    private void refreshHoldControls() {
        HoldBind bind = holdBindList.getSelectedValue();
        updatingHoldControls = true;
        try {
            boolean selected = bind != null;
            holdActionLabel.setText(selected ? "Action: " + holdActionName(bind) : "Action: Not set");
            setHoldActionButton.setEnabled(selected);
            holdIntervalSpinner.setEnabled(selected);
            holdPressSpinner.setEnabled(selected);
            if (selected) {
                holdIntervalSpinner.setValue(bind.intervalMs);
                holdPressSpinner.setValue(bind.pressMs);
            }
        } finally {
            updatingHoldControls = false;
        }
    }

    private void updateSelectedHoldTiming() {
        if (updatingHoldControls) return;
        HoldBind bind = holdBindList.getSelectedValue();
        if (bind == null) return;
        bind.intervalMs = ((Number) holdIntervalSpinner.getValue()).intValue();
        bind.pressMs = ((Number) holdPressSpinner.getValue()).intValue();
        boolean triggerDown;
        synchronized (physicallyDown) {
            triggerDown = physicallyDown.contains(bind.triggerKeyCode);
        }
        if (triggerDown) {
            stopHoldBind(bind);
            startHoldBind(bind);
        }
        persistBinds();
        holdBindList.repaint();
    }

    private void renameSelectedBind() {
        Bind bind = bindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Select a bind to rename.");
            return;
        }
        String name = promptForBindName("Rename bind", bind.displayName);
        if (name == null) return;
        bind.displayName = name;
        bindList.repaint();
        persistBinds();
        statusLabel.setText(name.isBlank()
                ? "Bind name cleared; trigger label restored."
                : "Renamed bind to " + name + ".");
    }

    private void renameSelectedHoldBind() {
        HoldBind bind = holdBindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Select a Hold bind to rename.");
            return;
        }
        String name = promptForBindName("Rename Hold bind", bind.displayName);
        if (name == null) return;
        bind.displayName = name;
        holdBindList.repaint();
        persistBinds();
        statusLabel.setText(name.isBlank()
                ? "Hold bind name cleared; trigger label restored."
                : "Renamed Hold bind to " + name + ".");
    }

    private String promptForBindName(String title, String currentName) {
        JTextField nameField = new JTextField(currentName == null ? "" : currentName, 24);
        JPanel content = new JPanel(new BorderLayout(0, 7));
        content.add(new JLabel("Name this bind (leave blank to use the trigger name):"), BorderLayout.NORTH);
        content.add(nameField, BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(this, content, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;
        String value = nameField.getText() == null ? "" : nameField.getText().trim().replaceAll("\\s+", " ");
        if (value.length() > 64) value = value.substring(0, 64).trim();
        return value;
    }

    private static String bindDisplayName(String customName, int triggerCode) {
        return customName == null || customName.isBlank() ? triggerName(triggerCode) : customName;
    }

    private static String bindDisplayName(String customName, List<Integer> triggerCodes) {
        return customName == null || customName.isBlank() ? triggerSummary(triggerCodes) : customName;
    }

    private static String triggerSummary(List<Integer> triggerCodes) {
        if (triggerCodes == null || triggerCodes.isEmpty()) return "No trigger";
        List<String> names = new ArrayList<>();
        synchronized (triggerCodes) {
            for (int code : triggerCodes) names.add("[" + triggerName(code) + "]");
        }
        return String.join("  ", names);
    }

    private boolean confirmBindRemoval(String type, String displayName) {
        if (!confirmDeleteCheck.isSelected()) return true;
        return JOptionPane.showConfirmDialog(this,
                "Remove '" + displayName + "'? This cannot be undone from the app.",
                "Remove " + type, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                == JOptionPane.YES_OPTION;
    }

    private void removeSelectedHoldBind() {
        int index = holdBindList.getSelectedIndex();
        if (index < 0) {
            statusLabel.setText("Select a hold bind to remove.");
            return;
        }
        HoldBind selected = holdBindListModel.get(index);
        if (!confirmBindRemoval("Hold bind", bindDisplayName(selected.displayName, selected.triggerKeyCode))) return;
        HoldBind removed = holdBindListModel.remove(index);
        stopHoldBind(removed);
        holdTriggerMap.remove(removed.triggerKeyCode, removed);
        int next = Math.min(index, holdBindListModel.size() - 1);
        holdBindList.setSelectedIndex(next);
        refreshHoldControls();
        persistBinds();
        statusLabel.setText("Removed hold bind " + bindDisplayName(removed.displayName, removed.triggerKeyCode) + ".");
    }

    private void toggleSelectedHoldBind() {
        HoldBind bind = holdBindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Select a hold bind first.");
            return;
        }
        bind.enabled = !bind.enabled;
        if (!bind.enabled) {
            stopHoldBind(bind);
        } else {
            synchronized (physicallyDown) {
                if (physicallyDown.contains(bind.triggerKeyCode)) startHoldBind(bind);
            }
        }
        holdBindList.repaint();
        persistBinds();
        statusLabel.setText(bindDisplayName(bind.displayName, bind.triggerKeyCode)
                + (bind.enabled ? " hold bind is enabled." : " hold bind is disabled."));
    }

    private void addCapturedSequenceAction(int triggerCode) {
        if (!capturingSequenceAction) return;
        capturingSequenceAction = false;
        setCursor(Cursor.getDefaultCursor());
        Bind bind = bindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("No bind is selected. Select a bind and try again.");
            return;
        }
        if (triggerCode == globalToggleTriggerCode) {
            statusLabel.setText("That input is reserved for the global enable/disable hotkey. Choose another output action.");
            return;
        }
        Step step;
        if (triggerCode < 0) {
            int button = -triggerCode;
            if (button < 1 || button > 5) {
                statusLabel.setText("That mouse button is not supported. Use buttons 1–5.");
                return;
            }
            step = Step.mouseClick(button);
        } else {
            step = Step.key(triggerCode);
        }
        synchronized (bind.steps) {
            if (bind.steps.size() >= MAX_STEPS_PER_BIND) {
                statusLabel.setText("This bind has reached the step limit.");
                return;
            }
            bind.steps.add(step);
        }
        refreshSteps();
        stepList.setSelectedIndex(stepListModel.size() - 1);
        persistBinds();
        statusLabel.setText("Added " + stepDescription(step) + " to " + bindDisplayName(bind.displayName, bind.triggerCodes) + "'s sequence.");
    }

    private void addBind(int triggerKeyCode) {
        // Several native events can be queued before Swing processes the first one.
        // Only the first event should complete the capture operation.
        if (!waitingForTrigger) return;
        waitingForTrigger = false;
        setCursor(Cursor.getDefaultCursor());
        if (triggerKeyCode == globalToggleTriggerCode) {
            statusLabel.setText("That trigger is reserved for the global enable/disable hotkey.");
            return;
        }
        if (triggerMap.containsKey(triggerKeyCode)) {
            statusLabel.setText("That trigger is already assigned to a Sequence bind. Choose a different trigger.");
            return;
        }
        if (holdTriggerMap.containsKey(triggerKeyCode) || macroTriggerMap.containsKey(triggerKeyCode)) {
            statusLabel.setText("That trigger is already used by another bind. Choose a different trigger.");
            return;
        }
        Bind bind = new Bind(triggerKeyCode);
        bindListModel.addElement(bind);
        triggerMap.put(triggerKeyCode, bind);
        bindList.setSelectedIndex(bindListModel.size() - 1);
        persistBinds();
        statusLabel.setText("Created bind for " + triggerName(triggerKeyCode) + ". You can add more triggers or build its sequence on the right.");
    }

    private void startHoldBind(HoldBind bind) {
        if (closing || !isHoldRuntimeEnabled() || bind == null || !bind.enabled || bind.actionType == HoldActionType.NONE) return;
        synchronized (physicallyDown) {
            if (!physicallyDown.contains(bind.triggerKeyCode)) return;
        }
        synchronized (bind) {
            if (bind.repeatTask != null && !bind.repeatTask.isDone()) return;
            bind.repeatTask = holdExecutor.scheduleAtFixedRate(() -> runHoldAction(bind), 0,
                    bind.intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void runHoldAction(HoldBind bind) {
        synchronized (physicallyDown) {
            if (!physicallyDown.contains(bind.triggerKeyCode) || !bind.enabled || !isHoldRuntimeEnabled() || closing) {
                stopHoldBind(bind);
                return;
            }
        }
        synchronized (bind) {
            if (bind.outputDown) return;
            try {
                pressHoldOutput(bind);
                bind.outputDown = true;
            } catch (RuntimeException exception) {
                SwingUtilities.invokeLater(() -> {
                    if (!closing) statusLabel.setText("Hold action error: " + exception.getMessage());
                });
                stopHoldBind(bind);
                return;
            }
        }

        try {
            Thread.sleep(bind.pressMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (bind) {
                if (bind.outputDown) {
                    releaseHoldOutput(bind);
                    bind.outputDown = false;
                }
            }
        }
    }

    private static boolean holdOutputMatchesTrigger(HoldBind bind) {
        if (bind.actionType == HoldActionType.KEY) return bind.triggerKeyCode == bind.actionCode;
        if (bind.actionType == HoldActionType.MOUSE) return bind.triggerKeyCode == -bind.actionCode;
        return false;
    }

    private void markSelfGeneratedRelease(HoldBind bind) {
        if (!holdOutputMatchesTrigger(bind)) return;
        long expiry = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10);
        selfGeneratedReleaseUntil.put(bind.triggerKeyCode, expiry);
    }

    private boolean consumeSelfGeneratedRelease(int triggerCode) {
        Long expiry = selfGeneratedReleaseUntil.get(triggerCode);
        if (expiry == null) return false;
        if (System.nanoTime() > expiry) {
            selfGeneratedReleaseUntil.remove(triggerCode, expiry);
            return false;
        }
        return selfGeneratedReleaseUntil.remove(triggerCode, expiry);
    }

    private void pressHoldOutput(HoldBind bind) {
        if (bind.actionType == HoldActionType.KEY) {
            robot.keyPress(bind.actionCode);
        } else if (bind.actionType == HoldActionType.MOUSE) {
            robot.mousePress(mouseButtonMask(bind.actionCode));
        }
    }

    private void releaseHoldOutput(HoldBind bind) {
        try {
            markSelfGeneratedRelease(bind);
            if (bind.actionType == HoldActionType.KEY) {
                robot.keyRelease(bind.actionCode);
            } else if (bind.actionType == HoldActionType.MOUSE) {
                robot.mouseRelease(mouseButtonMask(bind.actionCode));
            }
        } catch (RuntimeException exception) {
            // Continue stopping the hold runner even if an OS-level release fails.
        }
    }

    private void stopHoldBind(HoldBind bind) {
        stopHoldBind(bind, false);
    }

    private void stopHoldBind(HoldBind bind, boolean physicalTriggerReleased) {
        if (bind == null) return;
        ScheduledFuture<?> task;
        synchronized (bind) {
            task = bind.repeatTask;
            bind.repeatTask = null;
            if (task != null) task.cancel(true);
            // If the physical trigger is also the generated action, its real key-up or
            // button-up already releases that output. Do not send a second synthetic up.
            // When disabling or shutting down, release normally so no output is stuck.
            if (bind.outputDown) {
                if (!(physicalTriggerReleased && holdOutputMatchesTrigger(bind))) {
                    releaseHoldOutput(bind);
                }
                bind.outputDown = false;
            }
        }
    }

    private void stopAllHoldBinds() {
        for (HoldBind bind : holdTriggerMap.values()) stopHoldBind(bind);
    }

    private void removeSelectedBind() {
        int index = bindList.getSelectedIndex();
        if (index < 0) {
            statusLabel.setText("Select a bind to remove.");
            return;
        }
        Bind selected = bindListModel.get(index);
        if (!confirmBindRemoval("bind", bindDisplayName(selected.displayName, selected.triggerCodes))) return;
        Bind removed = bindListModel.remove(index);
        for (int trigger : removed.triggerCodes) triggerMap.remove(trigger, removed);
        int next = Math.min(index, bindListModel.size() - 1);
        bindList.setSelectedIndex(next);
        refreshSteps();
        persistBinds();
        statusLabel.setText("Removed bind " + bindDisplayName(removed.displayName, removed.triggerCodes) + ".");
    }

    private void toggleSelectedBind() {
        Bind bind = bindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Select a bind first.");
            return;
        }
        bind.enabled = !bind.enabled;
        bindList.repaint();
        persistBinds();
        statusLabel.setText(bindDisplayName(bind.displayName, bind.triggerCodes) + (bind.enabled ? " is enabled." : " is disabled."));
    }

    private void onBindSelectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) refreshSteps();
    }

    private void refreshSteps() {
        stepListModel.clear();
        bindList.repaint();
        Bind bind = bindList.getSelectedValue();
        updatingRepeatSpinner = true;
        try {
            if (bind == null) {
                sequenceHeading.setText("SEQUENCE  ·  SELECT A BIND");
                repeatSpinner.setValue(1);
                repeatSpinner.setEnabled(false);
                return;
            }
            sequenceHeading.setText("SEQUENCE  ·  " + bindDisplayName(bind.displayName, bind.triggerCodes));
            repeatSpinner.setValue(bind.repeatCount);
            repeatSpinner.setEnabled(true);
        } finally {
            updatingRepeatSpinner = false;
        }
        synchronized (bind.steps) {
            for (Step step : bind.steps) stepListModel.addElement(step);
        }
    }

    private void onRepeatCountChanged() {
        if (updatingRepeatSpinner) return;
        Bind bind = bindList.getSelectedValue();
        if (bind == null) return;
        bind.repeatCount = ((Number) repeatSpinner.getValue()).intValue();
        persistBinds();
        statusLabel.setText("Sequence repeat count set to " + bind.repeatCount + " for "
                + bindDisplayName(bind.displayName, bind.triggerCodes) + ".");
    }

    private void onDelayModeChanged() {
        if (updatingDelayMode) return;
        Object selected = delayModeCombo.getSelectedItem();
        if (!(selected instanceof DelayMode selectedMode)) return;
        delayMode = selectedMode;
        persistBinds();
        statusLabel.setText("Delay Mode set to " + selectedMode.label + ". " + selectedMode.description);
    }

    private void selectDelayMode(DelayMode mode) {
        updatingDelayMode = true;
        try {
            delayMode = mode;
            delayModeCombo.setSelectedItem(mode);
        } finally {
            updatingDelayMode = false;
        }
    }

    private void refreshMacroBlocks() {
        macroBlockListModel.clear();
        Macro macro = macroList.getSelectedValue();
        updatingMacroRepeatSpinner = true;
        try {
            if (macro == null) {
                macroBlockHeading.setText("MACRO BLOCKS  ·  SELECT A MACRO");
                macroTriggerSummary.setText("WHEN A TRIGGER IS PRESSED");
                macroRepeatSpinner.setValue(1);
                macroRepeatSpinner.setEnabled(false);
            } else {
                macroBlockHeading.setText("SCRATCH BLOCK WORKSPACE  ·  " + macro.name);
                macroTriggerSummary.setText("WHEN " + triggerSummary(macro.triggerCodes) + " IS PRESSED");
                macroRepeatSpinner.setValue(macro.repeatCount);
                macroRepeatSpinner.setEnabled(true);
                synchronized (macro.blocks) {
                    for (MacroBlock block : macro.blocks) macroBlockListModel.addElement(block);
                }
            }
        } finally {
            updatingMacroRepeatSpinner = false;
        }
        for (JButton button : macroToolboxButtons) button.setEnabled(macro != null);
        if (macroBlockWorkspaceHolder != null) {
            macroBlockWorkspaceHolder.removeAll();
            if (macro == null) {
                macroBlockWorkspaceHolder.add(createWorkspaceMessage("Select a macro to build its block stack."), BorderLayout.CENTER);
            } else if (macroBlockScroll != null) {
                // Keep the actual list visible even when empty so it remains a valid drop target.
                macroBlockList.setToolTipText(macro.blocks.isEmpty()
                        ? "Drag a block from the toolbox into this workspace to get started."
                        : "Drag blocks to reorder the stack. Double-click a block to edit it.");
                macroBlockWorkspaceHolder.add(macroBlockScroll, BorderLayout.CENTER);
            }
            macroBlockWorkspaceHolder.revalidate();
            macroBlockWorkspaceHolder.repaint();
        }
        macroList.repaint();
    }

    private JPanel createWorkspaceMessage(String message) {
        JPanel empty = new JPanel(new GridBagLayout());
        empty.setBackground(new Color(246, 248, 251));
        JLabel label = new JLabel("<html><div style='text-align:center;line-height:1.5'>" + message.replace("\n", "<br>") + "</div></html>");
        label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        label.setForeground(new Color(139, 148, 160));
        empty.add(label);
        return empty;
    }

    private void updateSelectedMacroRepeat() {
        if (updatingMacroRepeatSpinner) return;
        Macro macro = macroList.getSelectedValue();
        if (macro == null) return;
        macro.repeatCount = ((Number) macroRepeatSpinner.getValue()).intValue();
        macroList.repaint();
        persistBinds();
    }

    private void renameSelectedMacro() {
        Macro macro = macroList.getSelectedValue();
        if (macro == null) { statusLabel.setText("Select a macro first."); return; }
        String name = promptForBindName("Rename macro", macro.name);
        if (name == null) return;
        macro.name = name.isBlank() ? "Macro" : name;
        macroList.repaint();
        refreshMacroBlocks();
        persistBinds();
    }

    private void removeSelectedMacro() {
        int index = macroList.getSelectedIndex();
        if (index < 0) { statusLabel.setText("Select a macro first."); return; }
        Macro macro = macroListModel.get(index);
        if (confirmDeleteCheck.isSelected() && JOptionPane.showConfirmDialog(this,
                "Remove macro '" + macro.name + "'?", "Remove macro",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        macroListModel.remove(index);
        for (int trigger : macro.triggerCodes) macroTriggerMap.remove(trigger, macro);
        macroList.setSelectedIndex(Math.min(index, macroListModel.size() - 1));
        refreshMacroBlocks();
        persistBinds();
        statusLabel.setText("Removed macro " + macro.name + ".");
    }

    private void toggleSelectedMacro() {
        Macro macro = macroList.getSelectedValue();
        if (macro == null) { statusLabel.setText("Select a macro first."); return; }
        macro.enabled = !macro.enabled;
        macroList.repaint();
        persistBinds();
        statusLabel.setText(macro.name + (macro.enabled ? " enabled." : " disabled."));
    }

    private void editRandomReporterDefaults() {
        JSpinner minimum = new JSpinner(new SpinnerNumberModel(randomReporterMinimum, 1, MAX_WAIT_MS, 1));
        JSpinner maximum = new JSpinner(new SpinnerNumberModel(randomReporterMaximum, 1, MAX_WAIT_MS, 1));
        JPanel panel = new JPanel(new GridLayout(2, 3, 7, 6));
        panel.add(new JLabel("Random minimum:")); panel.add(minimum); panel.add(new JLabel(""));
        panel.add(new JLabel("Random maximum:")); panel.add(maximum); panel.add(new JLabel(""));
        if (JOptionPane.showConfirmDialog(this, panel, "Random number range", JOptionPane.OK_CANCEL_OPTION)
                == JOptionPane.OK_OPTION) {
            int min = ((Number) minimum.getValue()).intValue();
            int max = ((Number) maximum.getValue()).intValue();
            if (max < min) {
                JOptionPane.showMessageDialog(this, "Maximum must be at least the minimum.",
                        "Invalid random range", JOptionPane.WARNING_MESSAGE);
                return;
            }
            randomReporterMinimum = min;
            randomReporterMaximum = max;
            refreshRandomReporterButtonLabel();
            statusLabel.setText("Random number reporter set to " + min + "–" + max + ". Drag it onto a numbered [ ] slot.");
        }
    }

    private void refreshRandomReporterButtonLabel() {
        for (JButton button : macroToolboxButtons) {
            if (button.getToolTipText() != null && button.getToolTipText().startsWith("Drag onto a numbered")) {
                button.setText("Random " + randomReporterMinimum + "–" + randomReporterMaximum);
            }
        }
    }

    private void editRandomValueOperand(MacroBlock block) {
        JCheckBox useRandom = new JCheckBox("Use a random value", block.hasRandomValue());
        int currentMin = block.hasRandomValue() ? block.randomMinimum : 1;
        int currentMax = block.hasRandomValue() ? block.randomMaximum : 100;
        int maximumAllowed = maximumRandomValueFor(block);
        JSpinner minimum = new JSpinner(new SpinnerNumberModel(Math.min(currentMin, maximumAllowed), 1, maximumAllowed, 1));
        JSpinner maximum = new JSpinner(new SpinnerNumberModel(Math.min(currentMax, maximumAllowed), 1, maximumAllowed, 1));
        JPanel fields = new JPanel(new GridLayout(2, 2, 7, 6));
        fields.add(new JLabel("Minimum:")); fields.add(minimum);
        fields.add(new JLabel("Maximum:")); fields.add(maximum);
        useRandom.addActionListener(event -> {
            minimum.setEnabled(useRandom.isSelected());
            maximum.setEnabled(useRandom.isSelected());
        });
        minimum.setEnabled(useRandom.isSelected());
        maximum.setEnabled(useRandom.isSelected());
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(useRandom, BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, panel, "Edit number slot", JOptionPane.OK_CANCEL_OPTION)
                == JOptionPane.OK_OPTION) {
            if (useRandom.isSelected()) {
                int min = ((Number) minimum.getValue()).intValue();
                int max = ((Number) maximum.getValue()).intValue();
                if (max < min) {
                    JOptionPane.showMessageDialog(this, "Maximum must be at least the minimum.",
                            "Invalid random range", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                block.randomMinimum = min;
                block.randomMaximum = max;
            } else {
                block.randomMinimum = 0;
                block.randomMaximum = 0;
            }
            macroBlockList.repaint();
            persistBinds();
            statusLabel.setText(block.hasRandomValue() ? "Number slot now uses random "
                    + block.randomMinimum + "–" + block.randomMaximum + "." : "Random number removed from this slot.");
        }
    }

    private void addMacroBlock(MacroBlockType type) {
        if (type == MacroBlockType.RANDOM_NUMBER) {
            editRandomReporterDefaults();
            return;
        }
        if (type == MacroBlockType.END_REPEAT) {
            statusLabel.setText("End repeat is added automatically when you create a Repeat block.");
            return;
        }
        insertMacroBlock(type, -1);
    }

    private void insertMacroBlock(MacroBlockType type, int requestedIndex) {
        if (type == MacroBlockType.RANDOM_NUMBER) {
            statusLabel.setText("Random is a value block. Drag it onto a numbered [ ] input slot.");
            return;
        }
        Macro macro = macroList.getSelectedValue();
        if (macro == null) {
            statusLabel.setText("Create or select a macro first.");
            return;
        }
        if (type == null) return;

        boolean addClosingRepeat = type == MacroBlockType.REPEAT;
        boolean addClosingIf = type == MacroBlockType.FIND_IMAGE;
        int requiredSlots = (addClosingRepeat || addClosingIf) ? 2 : 1;
        if (macro.blocks.size() + requiredSlots > MAX_STEPS_PER_BIND) {
            statusLabel.setText("This macro has reached the block limit.");
            return;
        }

        MacroBlock block = switch (type) {
            case KEY -> MacroBlock.key(KeyEvent.VK_A, DEFAULT_HOLD_MS);
            case MOUSE -> MacroBlock.mouse(NativeMouseEvent.BUTTON1, DEFAULT_HOLD_MS);
            case WAIT -> MacroBlock.waitFor(250);
            case NOTIFY -> MacroBlock.notify("Macro notification");
            case TYPE_TEXT -> MacroBlock.typeText("Hello world", 12);
            case SCROLL -> MacroBlock.scroll(-1);
            case RANDOM_WAIT -> MacroBlock.randomWait(50, 150); // Legacy save compatibility; no longer in the toolbox.
            case RANDOM_NUMBER -> null;
            case REPEAT -> MacroBlock.repeat(3);
            case END_REPEAT -> null; // Matching close markers are inserted with Repeat.
            case STOP -> MacroBlock.marker(MacroBlockType.STOP);
            case BEEP -> MacroBlock.marker(MacroBlockType.BEEP);
            case COPY_TEXT -> MacroBlock.copyText("Text to copy");
            case COMMENT -> MacroBlock.comment("Add a note...");
            case MOVE_MOUSE -> MacroBlock.moveMouse(100, 100);
            case MOVE_MOUSE_REL -> MacroBlock.moveMouseRel(50, 0);
            case FIND_PIXEL -> MacroBlock.findPixel(0, 0, 200, 200, 0xFF0000, 15);
            case FIND_IMAGE -> MacroBlock.findImage();
            case END_IF -> MacroBlock.marker(MacroBlockType.END_IF);
        };
        if (block == null) return;

        int selectedIndex = macroBlockList.getSelectedIndex();
        int index = requestedIndex < 0
                ? (selectedIndex >= 0 ? selectedIndex + 1 : macro.blocks.size())
                : Math.max(0, Math.min(requestedIndex, macro.blocks.size()));

        synchronized (macro.blocks) {
            macro.blocks.add(index, block);
            if (addClosingRepeat) {
                macro.blocks.add(index + 1, MacroBlock.marker(MacroBlockType.END_REPEAT));
            } else if (addClosingIf) {
                macro.blocks.add(index + 1, MacroBlock.marker(MacroBlockType.END_IF));
            }
        }

        refreshMacroBlocks();
        // Keep Repeat selected so the next toolbox action is inserted into its body.
        // Subsequent actions remain inside the body until the user selects End Repeat.
        macroBlockList.setSelectedIndex(index);
        macroBlockList.ensureIndexIsVisible(index);
        persistBinds();

        if (type == MacroBlockType.KEY || type == MacroBlockType.MOUSE) {
            beginMacroBlockInputCapture(block);
        } else if (addClosingRepeat) {
            statusLabel.setText("Added Repeat with its matching End repeat. Select Repeat and add blocks to place them inside.");
        } else if (addClosingIf) {
            statusLabel.setText("Added Look for image with End if. Capture a small UI image, then add actions inside the block.");
            editSelectedMacroBlock();
        } else {
            statusLabel.setText("Added " + block.description() + " block.");
        }
    }

    private void beginMacroBlockInputCapture(MacroBlock block) {
        if (waitingForTrigger || capturingSequenceAction || capturingAdditionalSequenceTrigger || waitingForHoldTrigger
                || capturingHoldAction || capturingMacroTrigger || capturingMacroBlockInput || capturingGlobalToggleKey) return;
        pendingMacroBlockInput = block;
        capturingMacroBlockInput = true;
        statusLabel.setText("SET MACRO BLOCK INPUT  ·  press a key or click a mouse button");
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    private void addCapturedMacroBlockInput(int triggerCode) {
        if (!capturingMacroBlockInput) return;
        capturingMacroBlockInput = false;
        setCursor(Cursor.getDefaultCursor());
        MacroBlock block = pendingMacroBlockInput;
        pendingMacroBlockInput = null;
        if (block == null) return;
        if (triggerCode == globalToggleTriggerCode) {
            statusLabel.setText("That input is reserved for the global enable/disable hotkey. Choose another macro block action.");
            return;
        }
        if (triggerCode < 0) {
            int button = -triggerCode;
            if (!isSupportedMouseButton(button)) { statusLabel.setText("Unsupported mouse button."); return; }
            block.type = MacroBlockType.MOUSE;
            block.code = button;
        } else {
            block.type = MacroBlockType.KEY;
            block.code = triggerCode;
        }
        macroBlockList.repaint();
        persistBinds();
        statusLabel.setText("Updated macro block to " + block.description() + ".");
    }

    private void editSelectedMacroBlock() {
        Macro macro = macroList.getSelectedValue();
        MacroBlock block = macroBlockList.getSelectedValue();
        if (macro == null || block == null) { statusLabel.setText("Select a macro block first."); return; }
        if (block.hasRandomValue()) {
            editRandomValueOperand(block);
            return;
        }
        switch (block.type) {
            case KEY, MOUSE -> beginMacroBlockInputCapture(block);
            case WAIT -> {
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(block.milliseconds, 1, MAX_WAIT_MS, 10));
                JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                panel.add(new JLabel("Wait for:")); panel.add(spinner); panel.add(new JLabel("ms"));
                if (JOptionPane.showConfirmDialog(this, panel, "Edit wait block", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    block.milliseconds = ((Number) spinner.getValue()).intValue();
                    macroBlockList.repaint(); persistBinds();
                }
            }
            case RANDOM_WAIT -> {
                JSpinner minimum = new JSpinner(new SpinnerNumberModel(block.milliseconds, 1, MAX_WAIT_MS, 10));
                JSpinner maximum = new JSpinner(new SpinnerNumberModel(Math.max(block.milliseconds, block.code), 1, MAX_WAIT_MS, 10));
                JPanel panel = new JPanel(new GridLayout(2, 2, 6, 6));
                panel.add(new JLabel("Minimum wait:")); panel.add(minimum);
                panel.add(new JLabel("Maximum wait:")); panel.add(maximum);
                if (JOptionPane.showConfirmDialog(this, panel, "Edit random wait", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    int min = ((Number) minimum.getValue()).intValue();
                    int max = ((Number) maximum.getValue()).intValue();
                    if (max < min) {
                        JOptionPane.showMessageDialog(this, "Maximum wait must be at least the minimum.", "Invalid range", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    block.milliseconds = min; block.code = max;
                    macroBlockList.repaint(); persistBinds();
                }
            }
            case REPEAT -> {
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(Math.max(1, block.code), 1, MAX_REPEAT_COUNT, 1));
                JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                panel.add(new JLabel("Repeat enclosed blocks:")); panel.add(spinner); panel.add(new JLabel("times"));
                if (JOptionPane.showConfirmDialog(this, panel, "Edit repeat block", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    block.code = ((Number) spinner.getValue()).intValue();
                    macroBlockList.repaint(); persistBinds();
                }
            }
            case SCROLL -> {
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(block.code, -20, 20, 1));
                JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                panel.add(new JLabel("Wheel notches (negative = down, positive = up):")); panel.add(spinner);
                if (JOptionPane.showConfirmDialog(this, panel, "Edit scroll block", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    int amount = ((Number) spinner.getValue()).intValue();
                    if (amount == 0) {
                        JOptionPane.showMessageDialog(this, "Scroll amount cannot be zero.", "Invalid scroll amount", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    block.code = amount;
                    macroBlockList.repaint(); persistBinds();
                }
            }
            case TYPE_TEXT -> {
                JTextArea textArea = new JTextArea(block.text, 4, 24);
                textArea.setLineWrap(true);
                textArea.setWrapStyleWord(true);
                JSpinner interval = new JSpinner(new SpinnerNumberModel(block.milliseconds, 1, 1_000, 1));
                JPanel content = new JPanel(new BorderLayout(0, 7));
                content.add(new JScrollPane(textArea), BorderLayout.CENTER);
                JPanel timing = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
                timing.add(new JLabel("Delay between characters:")); timing.add(interval); timing.add(new JLabel("ms"));
                content.add(timing, BorderLayout.SOUTH);
                if (JOptionPane.showConfirmDialog(this, content, "Edit type text block", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    block.text = limitUtf(textArea.getText(), 180);
                    block.milliseconds = ((Number) interval.getValue()).intValue();
                    macroBlockList.repaint(); persistBinds();
                }
            }
            case NOTIFY, COPY_TEXT, COMMENT -> {
                String heading = switch (block.type) {
                    case NOTIFY -> "Notification text:";
                    case COPY_TEXT -> "Text to copy to clipboard:";
                    case COMMENT -> "Comment (for your own notes):";
                    default -> "Text:";
                };
                String updated = JOptionPane.showInputDialog(this, heading, block.text);
                if (updated != null) {
                    block.text = limitUtf(updated, 180);
                    macroBlockList.repaint(); persistBinds();
                }
            }
            case END_REPEAT, END_IF, STOP, BEEP -> statusLabel.setText("This block has no editable settings.");
            case RANDOM_NUMBER -> editRandomReporterDefaults();
            case MOVE_MOUSE, MOVE_MOUSE_REL -> {
                boolean relative = block.type == MacroBlockType.MOVE_MOUSE_REL;
                JSpinner xSpinner = new JSpinner(new SpinnerNumberModel(block.code, relative ? -10_000 : -20_000, relative ? 10_000 : 20_000, 1));
                JSpinner ySpinner = new JSpinner(new SpinnerNumberModel(block.milliseconds, relative ? -10_000 : -20_000, relative ? 10_000 : 20_000, 1));
                JPanel coordinates = new JPanel(new GridLayout(2, 2, 8, 6));
                coordinates.add(new JLabel(relative ? "Change X by:" : "Screen X:"));
                coordinates.add(xSpinner);
                coordinates.add(new JLabel(relative ? "Change Y by:" : "Screen Y:"));
                coordinates.add(ySpinner);
                JPanel panel = new JPanel(new BorderLayout(0, 8));
                panel.add(coordinates, BorderLayout.CENTER);
                if (!relative) {
                    JButton pickPoint = new JButton("Pick coordinates from screen…");
                    pickPoint.setToolTipText("Click the destination point on your desktop to fill X and Y.");
                    pickPoint.addActionListener(event -> showScreenSelectionOverlay(
                            "Click the point where the mouse should move · Esc cancels", false, picked -> {
                                xSpinner.setValue(picked.x);
                                ySpinner.setValue(picked.y);
                                statusLabel.setText("Picked screen position " + picked.x + "," + picked.y + ".");
                            }));
                    panel.add(pickPoint, BorderLayout.SOUTH);
                }
                String title = relative ? "Edit move mouse by" : "Edit move mouse to";
                if (JOptionPane.showConfirmDialog(this, panel, title, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    block.code = ((Number) xSpinner.getValue()).intValue();
                    block.milliseconds = ((Number) ySpinner.getValue()).intValue();
                    macroBlockList.repaint();
                    persistBinds();
                    statusLabel.setText("Updated " + block.description() + ".");
                }
            }
            case FIND_PIXEL -> editFindPixelBlock(block);
            case FIND_IMAGE -> editFindImageBlock(block);
        }
    }


private Rectangle virtualScreenBounds() {
    Rectangle bounds = null;
    for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
        Rectangle screen = device.getDefaultConfiguration().getBounds();
        bounds = bounds == null ? new Rectangle(screen) : bounds.union(screen);
    }
    return bounds == null ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()) : bounds;
}

/**
 * Shows a frozen screenshot overlay so the user can pick an absolute point or drag
 * a small reference region. The overlay is disposed before the callback runs.
 */
private void showScreenSelectionOverlay(String instruction, boolean dragToSelect,
        java.util.function.Consumer<Rectangle> onSelection) {
    final Rectangle desktopBounds = virtualScreenBounds();
    final BufferedImage desktopImage;
    try {
        desktopImage = robot.createScreenCapture(desktopBounds);
    } catch (Exception exception) {
        statusLabel.setText("Could not capture the desktop: " + exception.getMessage());
        return;
    }

    final JWindow overlay = new JWindow();
    overlay.setBounds(desktopBounds);
    overlay.setAlwaysOnTop(true);
    overlay.setFocusableWindowState(true);
    overlay.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

    final Point[] selectionPoints = new Point[2];
    JComponent canvas = new JComponent() {
        private static final long serialVersionUID = 1L;

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.drawImage(desktopImage, 0, 0, null);
                g.setColor(new Color(12, 18, 28, 72));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(20, 29, 43, 225));
                g.fillRoundRect(14, 14, Math.min(getWidth() - 28, 650), 42, 10, 10);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Segoe UI", Font.BOLD, 14));
                g.drawString(instruction, 27, 40);
                if (selectionPoints[0] != null && selectionPoints[1] != null && dragToSelect) {
                    int x = Math.min(selectionPoints[0].x, selectionPoints[1].x);
                    int y = Math.min(selectionPoints[0].y, selectionPoints[1].y);
                    int w = Math.abs(selectionPoints[0].x - selectionPoints[1].x);
                    int h = Math.abs(selectionPoints[0].y - selectionPoints[1].y);
                    g.setColor(new Color(55, 170, 255, 55));
                    g.fillRect(x, y, w, h);
                    g.setColor(new Color(25, 120, 220));
                    g.setStroke(new BasicStroke(2f));
                    g.drawRect(x, y, w, h);
                }
            } finally {
                g.dispose();
            }
        }
    };
    canvas.setFocusable(true);
    // Keep the current drag rectangle visible while selecting a reference image.
    canvas.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override
        public void mousePressed(java.awt.event.MouseEvent event) {
            selectionPoints[0] = event.getPoint();
            selectionPoints[1] = event.getPoint();
            canvas.repaint();
        }

        @Override
        public void mouseReleased(java.awt.event.MouseEvent event) {
            if (selectionPoints[0] == null) return;
            selectionPoints[1] = event.getPoint();
            Rectangle local = new Rectangle(
                    Math.min(selectionPoints[0].x, selectionPoints[1].x),
                    Math.min(selectionPoints[0].y, selectionPoints[1].y),
                    Math.max(1, Math.abs(selectionPoints[0].x - selectionPoints[1].x)),
                    Math.max(1, Math.abs(selectionPoints[0].y - selectionPoints[1].y)));
            if (dragToSelect && (local.width < 5 || local.height < 5)) {
                selectionPoints[0] = null;
                selectionPoints[1] = null;
                canvas.repaint();
                statusLabel.setText("Drag a rectangle at least 5 × 5 pixels around the item.");
                return;
            }
            Rectangle selected = new Rectangle(
                    desktopBounds.x + local.x,
                    desktopBounds.y + local.y,
                    dragToSelect ? local.width : 0,
                    dragToSelect ? local.height : 0);
            overlay.dispose();
            SwingUtilities.invokeLater(() -> onSelection.accept(selected));
        }
    });
    canvas.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
        @Override
        public void mouseDragged(java.awt.event.MouseEvent event) {
            selectionPoints[1] = event.getPoint();
            canvas.repaint();
        }

        @Override
        public void mouseMoved(java.awt.event.MouseEvent event) {
            if (selectionPoints[0] != null) {
                selectionPoints[1] = event.getPoint();
                canvas.repaint();
            }
        }
    });
    canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelScreenPicker");
    canvas.getActionMap().put("cancelScreenPicker", new AbstractAction() {
        private static final long serialVersionUID = 1L;
        @Override
        public void actionPerformed(java.awt.event.ActionEvent event) {
            overlay.dispose();
            statusLabel.setText("Screen picker cancelled.");
        }
    });
    overlay.setContentPane(canvas);
    overlay.setVisible(true);
    canvas.requestFocusInWindow();
}

private void editFindImageBlock(MacroBlock block) {
    JTextField pathField = new JTextField(block.text, 30);
    pathField.setEditable(false);
    JLabel referenceStatus = new JLabel(block.text.isBlank()
            ? "No visual reference captured yet."
            : "Saved reference: " + Path.of(block.text).getFileName());
    JButton captureButton = new JButton("Capture a small image from screen…");
    captureButton.setToolTipText("Drag a tight rectangle around a UI icon, button, or other desktop item.");
    captureButton.addActionListener(event -> showScreenSelectionOverlay(
            "Drag around the UI image to look for · Esc cancels", true, selected -> {
                try {
                    if (selected.width > 256 || selected.height > 256) {
                        JOptionPane.showMessageDialog(this,
                                "Choose a smaller area (maximum 256 × 256 pixels).",
                                "Reference image too large", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    BufferedImage captured = robot.createScreenCapture(selected);
                    Path directory = Path.of(System.getProperty("user.home"), ".kss", "targets");
                    Files.createDirectories(directory);
                    Path target = directory.resolve("visual-" + System.nanoTime() + ".png");
                    if (!ImageIO.write(captured, "png", target.toFile())) {
                        throw new IOException("PNG image writer is unavailable.");
                    }
                    String savedPath = target.toAbsolutePath().toString();
                    if (savedPath.length() > 180) {
                        Files.deleteIfExists(target);
                        throw new IOException("The saved reference path is too long for the macro settings file.");
                    }
                    pathField.setText(savedPath);
                    referenceStatus.setText("Saved reference: " + target.getFileName());
                    statusLabel.setText("Captured visual reference " + target.getFileName() + ".");
                } catch (Exception exception) {
                    statusLabel.setText("Could not save visual reference: " + exception.getMessage());
                }
            }));
    JPanel panel = new JPanel(new BorderLayout(0, 8));
    panel.add(new JLabel("<html><b>Look for image</b><br>Drag a small rectangle around a desktop UI icon or button. When this same image is visible on the desktop, blocks between this block and End if will run.</html>"), BorderLayout.NORTH);
    JPanel center = new JPanel(new BorderLayout(0, 5));
    center.add(captureButton, BorderLayout.NORTH);
    center.add(pathField, BorderLayout.CENTER);
    center.add(referenceStatus, BorderLayout.SOUTH);
    panel.add(center, BorderLayout.CENTER);
    if (JOptionPane.showConfirmDialog(this, panel, "Edit Look for image block",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
        String path = pathField.getText().trim();
        if (path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            JOptionPane.showMessageDialog(this, "Capture a visual reference before saving this block.",
                    "Visual reference required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        block.text = path;
        macroBlockList.repaint();
        persistBinds();
        statusLabel.setText("Saved Look for image block. Add actions between it and End if.");
    }
}

    private void editFindPixelBlock(MacroBlock block) {
        JSpinner xSpinner = new JSpinner(new SpinnerNumberModel(block.searchX, -10_000, 20_000, 1));
        JSpinner ySpinner = new JSpinner(new SpinnerNumberModel(block.searchY, -10_000, 20_000, 1));
        JSpinner wSpinner = new JSpinner(new SpinnerNumberModel(Math.max(1, block.searchW), 1, 8_000, 1));
        JSpinner hSpinner = new JSpinner(new SpinnerNumberModel(Math.max(1, block.searchH), 1, 8_000, 1));
        int rgb = block.colorRgb & 0xFFFFFF;
        JSpinner rSpinner = new JSpinner(new SpinnerNumberModel((rgb >> 16) & 0xFF, 0, 255, 1));
        JSpinner gSpinner = new JSpinner(new SpinnerNumberModel((rgb >> 8) & 0xFF, 0, 255, 1));
        JSpinner bSpinner = new JSpinner(new SpinnerNumberModel(rgb & 0xFF, 0, 255, 1));
        JSpinner tolSpinner = new JSpinner(new SpinnerNumberModel(Math.max(0, Math.min(255, block.colorTolerance)), 0, 255, 1));

        JPanel colorPreview = new JPanel();
        colorPreview.setPreferredSize(new Dimension(48, 24));
        colorPreview.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        Runnable updatePreview = () -> {
            int r = ((Number) rSpinner.getValue()).intValue();
            int g = ((Number) gSpinner.getValue()).intValue();
            int b = ((Number) bSpinner.getValue()).intValue();
            colorPreview.setBackground(new Color(r, g, b));
            colorPreview.repaint();
        };
        updatePreview.run();
        rSpinner.addChangeListener(e -> updatePreview.run());
        gSpinner.addChangeListener(e -> updatePreview.run());
        bSpinner.addChangeListener(e -> updatePreview.run());

        JButton sampleButton = new JButton("Sample color under mouse");
        sampleButton.setToolTipText("Reads the pixel color at the current mouse position.");
        sampleButton.addActionListener(e -> {
            try {
                Point p = MouseInfo.getPointerInfo().getLocation();
                Color c = robot.getPixelColor(p.x, p.y);
                rSpinner.setValue(c.getRed());
                gSpinner.setValue(c.getGreen());
                bSpinner.setValue(c.getBlue());
                statusLabel.setText(String.format(Locale.ROOT, "Sampled color #%02X%02X%02X at %d,%d",
                        c.getRed(), c.getGreen(), c.getBlue(), p.x, p.y));
            } catch (Exception ex) {
                statusLabel.setText("Could not sample pixel: " + ex.getMessage());
            }
        });

        JButton useMouseAsTopLeft = new JButton("Use mouse as top-left");
        useMouseAsTopLeft.setToolTipText("Sets region X/Y from the current mouse position.");
        useMouseAsTopLeft.addActionListener(e -> {
            Point p = MouseInfo.getPointerInfo().getLocation();
            xSpinner.setValue(p.x);
            ySpinner.setValue(p.y);
            statusLabel.setText("Region top-left set to " + p.x + "," + p.y);
        });

        JPanel region = new JPanel(new GridLayout(2, 4, 6, 4));
        region.add(new JLabel("X:")); region.add(xSpinner);
        region.add(new JLabel("Y:")); region.add(ySpinner);
        region.add(new JLabel("Width:")); region.add(wSpinner);
        region.add(new JLabel("Height:")); region.add(hSpinner);

        JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        colorRow.add(new JLabel("R:")); colorRow.add(rSpinner);
        colorRow.add(new JLabel("G:")); colorRow.add(gSpinner);
        colorRow.add(new JLabel("B:")); colorRow.add(bSpinner);
        colorRow.add(colorPreview);
        colorRow.add(sampleButton);

        JPanel tolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tolRow.add(new JLabel("Color tolerance (per channel 0–255):"));
        tolRow.add(tolSpinner);
        tolRow.add(useMouseAsTopLeft);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(new JLabel("<html><b>Search region</b> (absolute screen coordinates). Blocks between this and <b>End if</b> only run when a matching pixel is found.</html>"), BorderLayout.NORTH);
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(region);
        center.add(Box.createVerticalStrut(6));
        center.add(colorRow);
        center.add(Box.createVerticalStrut(4));
        center.add(tolRow);
        content.add(center, BorderLayout.CENTER);

        if (JOptionPane.showConfirmDialog(this, content, "Edit If pixel found", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            block.searchX = ((Number) xSpinner.getValue()).intValue();
            block.searchY = ((Number) ySpinner.getValue()).intValue();
            block.searchW = Math.max(1, ((Number) wSpinner.getValue()).intValue());
            block.searchH = Math.max(1, ((Number) hSpinner.getValue()).intValue());
            int r = ((Number) rSpinner.getValue()).intValue();
            int g = ((Number) gSpinner.getValue()).intValue();
            int b = ((Number) bSpinner.getValue()).intValue();
            block.colorRgb = (r << 16) | (g << 8) | b;
            block.colorTolerance = ((Number) tolSpinner.getValue()).intValue();
            macroBlockList.repaint();
            persistBinds();
            statusLabel.setText("Updated " + block.description() + ".");
        }
    }

    private void moveSelectedMacroBlock(int direction) {
        Macro macro = macroList.getSelectedValue();
        int index = macroBlockList.getSelectedIndex();
        if (macro == null || index < 0) return;
        int destination = index + direction;
        if (destination < 0 || destination >= macro.blocks.size()) return;
        synchronized (macro.blocks) {
            MacroBlock block = macro.blocks.remove(index);
            macro.blocks.add(destination, block);
        }
        refreshMacroBlocks();
        macroBlockList.setSelectedIndex(destination);
        persistBinds();
    }

    private void removeSelectedMacroBlock() {
        Macro macro = macroList.getSelectedValue();
        int index = macroBlockList.getSelectedIndex();
        if (macro == null || index < 0) return;

        MacroBlock selected = macro.blocks.get(index);
        int partner = -1;
        if (selected.type == MacroBlockType.REPEAT) {
            partner = findMatchingEndRepeat(macro.blocks, index);
        } else if (selected.type == MacroBlockType.END_REPEAT) {
            partner = findMatchingStartRepeat(macro.blocks, index);
        }

        synchronized (macro.blocks) {
            if (partner >= 0 && partner < macro.blocks.size()) {
                int first = Math.min(index, partner);
                int second = Math.max(index, partner);
                macro.blocks.remove(second);
                macro.blocks.remove(first);
            } else {
                macro.blocks.remove(index);
            }
        }
        refreshMacroBlocks();
        if (!macroBlockListModel.isEmpty()) {
            macroBlockList.setSelectedIndex(Math.min(index, macroBlockListModel.size() - 1));
        }
        persistBinds();
        statusLabel.setText(partner >= 0
                ? "Removed the Repeat block and its matching End repeat."
                : "Removed macro block.");
    }

    private static int findMatchingEndRepeat(List<MacroBlock> blocks, int startIndex) {
        int depth = 0;
        for (int i = startIndex; i < blocks.size(); i++) {
            MacroBlockType type = blocks.get(i).type;
            if (type == MacroBlockType.REPEAT) depth++;
            else if (type == MacroBlockType.END_REPEAT && --depth == 0) return i;
        }
        return -1;
    }

    private static int findMatchingStartRepeat(List<MacroBlock> blocks, int endIndex) {
        int depth = 0;
        for (int i = endIndex; i >= 0; i--) {
            MacroBlockType type = blocks.get(i).type;
            if (type == MacroBlockType.END_REPEAT) depth++;
            else if (type == MacroBlockType.REPEAT && --depth == 0) return i;
        }
        return -1;
    }

    private void renameMacroTriggerLabel(Macro macro) {
        // Reserved for a future per-trigger label; the actual hotkeys are never modified by renaming the macro.
    }

    private void setMillisecondsForAllActions() {
        Bind bind = bindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Select a bind first.");
            return;
        }
        int actionCount = 0;
        synchronized (bind.steps) {
            for (Step step : bind.steps) {
                if (step.type == StepType.KEY || step.type == StepType.MOUSE) actionCount++;
            }
        }
        if (actionCount == 0) {
            statusLabel.setText("This sequence has no key or mouse actions to update.");
            return;
        }

        int suggestedMs = DEFAULT_HOLD_MS;
        synchronized (bind.steps) {
            for (Step step : bind.steps) {
                if (step.type == StepType.KEY || step.type == StepType.MOUSE) {
                    suggestedMs = step.holdMs;
                    break;
                }
            }
        }
        Integer milliseconds = showMillisecondsDialog(
                "Set ms for all",
                "Hold duration for every key and mouse action (wait steps are unchanged)",
                suggestedMs, MAX_HOLD_MS, "Apply to all actions");
        if (milliseconds == null) return;

        int selectedIndex = stepList.getSelectedIndex();
        synchronized (bind.steps) {
            for (int i = 0; i < bind.steps.size(); i++) {
                Step step = bind.steps.get(i);
                if (step.type == StepType.KEY) {
                    bind.steps.set(i, Step.key(step.keyCode, milliseconds));
                } else if (step.type == StepType.MOUSE) {
                    bind.steps.set(i, Step.mouseClick(step.mouseButton, milliseconds));
                }
            }
        }
        refreshSteps();
        if (!stepListModel.isEmpty() && selectedIndex >= 0) {
            stepList.setSelectedIndex(Math.min(selectedIndex, stepListModel.size() - 1));
        }
        persistBinds();
        statusLabel.setText("Set " + milliseconds + " ms for " + actionCount
                + " key/mouse action(s). Wait steps were left unchanged.");
    }

    private void addWaitStep() {
        Bind bind = bindList.getSelectedValue();
        if (bind == null) {
            statusLabel.setText("Add or select a bind first.");
            return;
        }
        Integer milliseconds = showMillisecondsDialog("Add wait", "Pause before the next action", 250, MAX_WAIT_MS, "Add wait step");
        if (milliseconds == null) return;
        Step step = Step.waitFor(milliseconds);
        synchronized (bind.steps) {
            if (bind.steps.size() >= MAX_STEPS_PER_BIND) {
                statusLabel.setText("This bind has reached the step limit.");
                return;
            }
            bind.steps.add(step);
        }
        refreshSteps();
        stepList.setSelectedIndex(stepListModel.size() - 1);
        persistBinds();
        statusLabel.setText("Added a " + step.waitMs + " ms wait.");
    }

    private void editSelectedTiming() {
        Bind bind = bindList.getSelectedValue();
        int index = stepList.getSelectedIndex();
        if (bind == null || index < 0) {
            statusLabel.setText("Select a key, mouse, or wait step first.");
            return;
        }
        Step existing;
        synchronized (bind.steps) {
            if (index >= bind.steps.size()) return;
            existing = bind.steps.get(index);
        }
        boolean waitStep = existing.type == StepType.WAIT;
        int currentMs = waitStep ? existing.waitMs : existing.holdMs;
        int maximum = waitStep ? MAX_WAIT_MS : MAX_HOLD_MS;
        String description = waitStep ? "Wait duration" : "Button hold duration";
        Integer milliseconds = showMillisecondsDialog("Edit timing", description, currentMs, maximum, "Edit milliseconds");
        if (milliseconds == null) return;
        Step replacement = switch (existing.type) {
            case KEY -> Step.key(existing.keyCode, milliseconds);
            case MOUSE -> Step.mouseClick(existing.mouseButton, milliseconds);
            case WAIT -> Step.waitFor(milliseconds);
        };
        synchronized (bind.steps) {
            if (index >= bind.steps.size()) return;
            bind.steps.set(index, replacement);
        }
        refreshSteps();
        stepList.setSelectedIndex(index);
        persistBinds();
        statusLabel.setText((waitStep ? "Wait set to " : "Press duration set to ") + milliseconds + " ms.");
    }

    private Integer showMillisecondsDialog(String title, String description, int initialValue, int maximum, String heading) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialValue, 1, maximum, 1));
        spinner.setPreferredSize(new Dimension(110, 28));
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setColumns(7);
        }

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(new JLabel(description + " (1–" + maximum + " ms)"));
        content.add(Box.createVerticalStrut(8));
        JPanel valueRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        valueRow.add(spinner);
        valueRow.add(new JLabel("ms"));
        content.add(valueRow);
        content.add(Box.createVerticalStrut(6));

        JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (int preset : new int[]{10, 20, 50, 100, 250, 500}) {
            if (preset > maximum) continue;
            JButton presetButton = new JButton(Integer.toString(preset));
            presetButton.addActionListener(event -> spinner.setValue(preset));
            presets.add(presetButton);
        }
        content.add(presets);

        int result = JOptionPane.showConfirmDialog(this, content, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;
        try {
            spinner.commitEdit();
            int value = ((Number) spinner.getValue()).intValue();
            return value >= 1 && value <= maximum ? value : null;
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this,
                    "Enter a value between 1 and " + maximum + " milliseconds.",
                    "Invalid timing", JOptionPane.WARNING_MESSAGE);
            return null;
        }
    }

    private static String stepDescription(Step step) {
        return switch (step.type) {
            case KEY -> "key " + keyName(step.keyCode);
            case MOUSE -> mouseStepName(step.mouseButton);
            case WAIT -> step.waitMs + " ms wait";
        };
    }

    private void removeSelectedStep() {
        Bind bind = bindList.getSelectedValue();
        int index = stepList.getSelectedIndex();
        if (bind == null || index < 0) {
            statusLabel.setText("Select a sequence step to remove.");
            return;
        }
        synchronized (bind.steps) {
            bind.steps.remove(index);
        }
        refreshSteps();
        if (!stepListModel.isEmpty()) stepList.setSelectedIndex(Math.min(index, stepListModel.size() - 1));
        persistBinds();
        statusLabel.setText("Removed sequence step.");
    }

    private void moveSelectedStep(int direction) {
        Bind bind = bindList.getSelectedValue();
        int index = stepList.getSelectedIndex();
        if (bind == null || index < 0) {
            statusLabel.setText("Select a sequence step first.");
            return;
        }
        int destination = index + direction;
        if (destination < 0 || destination >= bind.steps.size()) return;
        synchronized (bind.steps) {
            Step step = bind.steps.remove(index);
            bind.steps.add(destination, step);
        }
        refreshSteps();
        stepList.setSelectedIndex(destination);
        persistBinds();
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent event) {
        int awtKeyCode = nativeKeyToAwt(event.getKeyCode());
        if (awtKeyCode == KeyEvent.VK_UNDEFINED) return;

        synchronized (physicallyDown) {
            if (!physicallyDown.add(awtKeyCode)) return; // Ignore OS key-repeat events.
        }

        handleTriggerPressed(awtKeyCode);
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent event) {
        int awtKeyCode = nativeKeyToAwt(event.getKeyCode());
        if (consumeSelfGeneratedRelease(awtKeyCode)) return;
        synchronized (physicallyDown) {
            physicallyDown.remove(awtKeyCode);
        }
        HoldBind holdBind = holdTriggerMap.get(awtKeyCode);
        if (holdBind != null) stopHoldBind(holdBind, true);
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent event) {
        // Typed events are not needed; only key-down events are used as triggers.
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent event) {
        // Only true button-down events are eligible. Wheel events have their own listener
        // and must never be interpreted as Mouse 4 / Mouse 5.
        if (event.getID() != NativeMouseEvent.NATIVE_MOUSE_PRESSED) return;

        int button = event.getButton();
        if (!isSupportedMouseButton(button)) return;

        // Mouse trigger IDs use negative numbers, entirely separate from keyboard key codes.
        int triggerCode = mouseTriggerCode(button);
        synchronized (physicallyDown) {
            if (!physicallyDown.add(triggerCode)) return;
        }
        handleTriggerPressed(triggerCode);
    }

    @Override
    public void nativeMouseReleased(NativeMouseEvent event) {
        int button = event.getButton();
        if (!isSupportedMouseButton(button)) return;
        int triggerCode = mouseTriggerCode(button);
        if (consumeSelfGeneratedRelease(triggerCode)) return;
        synchronized (physicallyDown) {
            physicallyDown.remove(triggerCode);
        }
        HoldBind holdBind = holdTriggerMap.get(triggerCode);
        if (holdBind != null) stopHoldBind(holdBind, true);
    }

    @Override
    public void nativeMouseClicked(NativeMouseEvent event) {
        // The pressed event is used so a trigger does not fire twice per click.
    }

    @Override
    public void nativeMouseWheelMoved(NativeMouseWheelEvent event) {
        // Scrolling is never a bind trigger or a sequence action. Showing this message only
        // while capturing prevents users from mistaking wheel movement for Mouse 4 / Mouse 5.
        if (!waitingForTrigger && !capturingSequenceAction && !capturingAdditionalSequenceTrigger && !waitingForHoldTrigger
                && !capturingHoldAction && !capturingMacroTrigger && !capturingMacroBlockInput && !capturingGlobalToggleKey) return;
        SwingUtilities.invokeLater(() -> {
            if (waitingForTrigger || waitingForHoldTrigger || capturingMacroTrigger) {
                statusLabel.setText("SCROLL WHEEL IGNORED  ·  click a mouse button or press a keyboard key");
            } else if (capturingSequenceAction || capturingAdditionalSequenceTrigger || capturingHoldAction || capturingMacroBlockInput || capturingGlobalToggleKey) {
                statusLabel.setText("SCROLL WHEEL IGNORED  ·  click Mouse Left, Middle, Right, Mouse 4 or Mouse 5");
            }
        });
    }

    private void handleTriggerPressed(int triggerCode) {
        if (closing) return;

        if (capturingGlobalToggleKey) {
            if (triggerCode > 0 && isModifierKey(triggerCode)) return;
            SwingUtilities.invokeLater(() -> captureGlobalToggleKey(triggerCode));
            return;
        }

        if (capturingAdditionalSequenceTrigger) {
            if (triggerCode > 0 && isModifierKey(triggerCode)) return;
            SwingUtilities.invokeLater(() -> addAdditionalSequenceTrigger(triggerCode));
            return;
        }

        if (capturingMacroTrigger) {
            if (triggerCode > 0 && isModifierKey(triggerCode)) return;
            SwingUtilities.invokeLater(() -> addCapturedMacroTrigger(triggerCode));
            return;
        }

        if (capturingMacroBlockInput) {
            if (triggerCode > 0 && isModifierKey(triggerCode)) return;
            SwingUtilities.invokeLater(() -> addCapturedMacroBlockInput(triggerCode));
            return;
        }

        if (capturingSequenceAction) {
            if (triggerCode > 0 && isModifierKey(triggerCode)) return;
            SwingUtilities.invokeLater(() -> addCapturedSequenceAction(triggerCode));
            return;
        }

        if (capturingHoldAction) {
            if (triggerCode > 0 && isModifierKey(triggerCode)) return;
            SwingUtilities.invokeLater(() -> addCapturedHoldAction(triggerCode));
            return;
        }

        if (waitingForHoldTrigger) {
            if (triggerCode > 0 && isModifierKey(triggerCode)) return;
            SwingUtilities.invokeLater(() -> addHoldBind(triggerCode));
            return;
        }

        if (waitingForTrigger) {
            if (triggerCode > 0 && isModifierKey(triggerCode)) return;
            SwingUtilities.invokeLater(() -> addBind(triggerCode));
            return;
        }

        // The global toggle is reserved as a trigger, but action-capture modes above
        // are allowed to finish first. Output actions matching this key are rejected.
        if (globalToggleTriggerCode != 0 && triggerCode == globalToggleTriggerCode) {
            toggleGlobalGroupsFromHotkey();
            return;
        }

        HoldBind holdBind = holdTriggerMap.get(triggerCode);
        if (holdBind != null && holdBind.enabled && isHoldRuntimeEnabled()) {
            startHoldBind(holdBind);
            showNotification("Hold started", bindDisplayName(holdBind.displayName, holdBind.triggerKeyCode));
        }

        Macro matchingMacro = macroTriggerMap.get(triggerCode);
        if (matchingMacro != null) {
            if (!matchingMacro.enabled || !isMacrosRuntimeEnabled()) return;
            if (macroRunning.get()) {
                SwingUtilities.invokeLater(() -> statusLabel.setText(
                        "Macro '" + matchingMacro.name + "' was triggered, but another automation is still running."));
                return;
            }
            List<MacroBlock> blocks = new ArrayList<>();
            synchronized (matchingMacro.blocks) {
                for (MacroBlock block : matchingMacro.blocks) blocks.add(block.copy());
            }
            String macroName = matchingMacro.name;
            int macroRepeats = matchingMacro.repeatCount;
            if (blocks.isEmpty()) {
                SwingUtilities.invokeLater(() -> statusLabel.setText(
                        "Macro '" + macroName + "' has no blocks yet. Add blocks in the Macros tab."));
                return;
            }
            logMacro("Trigger received: " + macroName + " (" + triggerName(triggerCode) + ")");
            if (!submitAutomation(AutomationCategory.MACRO,
                    () -> runMacro(blocks, macroRepeats, macroName))) {
                SwingUtilities.invokeLater(() -> statusLabel.setText(
                        "Could not start macro '" + macroName + "'. Another automation may be running."));
                return;
            }
            showNotification("Macro started", macroName);
            return;
        }

        if (macroRunning.get()) return;
        Bind matching = findEnabledBind(triggerCode);
        if (matching == null) return;

        // Copy the steps so editing the UI cannot mutate a sequence while it is running.
        List<Step> sequence;
        synchronized (matching.steps) {
            if (matching.steps.isEmpty()) return;
            sequence = new ArrayList<>(matching.steps);
        }
        int repeatCount = matching.repeatCount;
        DelayMode selectedDelayMode = delayMode;
        String triggerLabel = triggerSummary(matching.triggerCodes);
        if (!submitAutomation(AutomationCategory.SEQUENCE,
                () -> runSequence(sequence, triggerLabel, repeatCount, selectedDelayMode))) return;
        showNotification("Bind started", bindDisplayName(matching.displayName, matching.triggerCodes));
    }

    private Bind findEnabledBind(int triggerKeyCode) {
        if (!isSequenceRuntimeEnabled()) return null;
        Bind bind = triggerMap.get(triggerKeyCode);
        return bind != null && bind.enabled ? bind : null;
    }

    private boolean submitAutomation(AutomationCategory category, Runnable runner) {
        final long runId;
        final FutureTask<Void> task;
        synchronized (automationStateLock) {
            if (macroRunning.get()) return false;
            macroRunning.set(true);
            runId = ++automationRunCounter;
            activeAutomationRunId = runId;
            activeAutomationCategory = category;
            java.util.concurrent.atomic.AtomicBoolean workerStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
            task = new FutureTask<>(() -> {
                try {
                    runner.run();
                } finally {
                    clearAutomationRun(runId);
                }
                return null;
            }) {
                @Override
                public void run() {
                    workerStarted.set(true);
                    try {
                        super.run();
                    } finally {
                        // Also clears when a FutureTask is cancelled after the worker is
                        // dequeued but before its callable starts.
                        clearAutomationRun(runId);
                    }
                }

                @Override
                protected void done() {
                    // If cancellation happened before the executor began this task,
                    // its callable/run() may never execute to clear the active state.
                    if (isCancelled() && !workerStarted.get()) clearAutomationRun(runId);
                }
            };
            activeAutomationFuture = task;
        }
        try {
            macroExecutor.execute(task);
            return true;
        } catch (RuntimeException exception) {
            clearAutomationRun(runId);
            SwingUtilities.invokeLater(() -> statusLabel.setText("Could not start automation: " + exception.getMessage()));
            return false;
        }
    }

    private void clearAutomationRun(long runId) {
        synchronized (automationStateLock) {
            if (activeAutomationRunId != runId) return;
            activeAutomationFuture = null;
            activeAutomationCategory = null;
            activeAutomationRunId = 0;
            macroRunning.set(false);
        }
    }

    private void runSequence(List<Step> sequence, String triggerLabel, int repeatCount, DelayMode runDelayMode) {
        try {
            for (int run = 0; run < repeatCount && !closing && isSequenceRuntimeEnabled(); run++) {
                for (Step step : sequence) {
                    if (closing || Thread.currentThread().isInterrupted() || !isSequenceRuntimeEnabled()) break;
                    if (step.type == StepType.WAIT) {
                        Thread.sleep(step.waitMs);
                    } else if (step.type == StepType.KEY) {
                        int holdMs = runDelayMode.resolveHoldMs(step.holdMs);
                        robot.keyPress(step.keyCode);
                        try {
                            if (holdMs > 0) Thread.sleep(holdMs);
                        } finally {
                            robot.keyRelease(step.keyCode);
                        }
                    } else if (step.type == StepType.MOUSE) {
                        int mask = mouseButtonMask(step.mouseButton);
                        int holdMs = runDelayMode.resolveHoldMs(step.holdMs);
                        robot.mousePress(mask);
                        try {
                            if (holdMs > 0) Thread.sleep(holdMs);
                        } finally {
                            robot.mouseRelease(mask);
                        }
                    }
                }
            }
            SwingUtilities.invokeLater(() -> {
                if (!closing && isSequenceRuntimeEnabled()) statusLabel.setText("Ran " + sequence.size() + " steps × " + repeatCount
                        + " for " + triggerLabel + " (" + runDelayMode.label + " delay).");
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            SwingUtilities.invokeLater(() -> {
                if (!closing) statusLabel.setText("Sequence error: " + exception.getMessage());
            });
        }
    }

    /** Appends a line to the Macros activity log and mirrors a short version to the status bar. Thread-safe for the automation thread. */
    private void logMacro(String message) {
        if (message == null || message.isBlank()) return;
        String line = message.trim();
        SwingUtilities.invokeLater(() -> {
            if (closing) return;
            if (macroActivityLog.getDocument().getLength() > 40_000) {
                try {
                    macroActivityLog.getDocument().remove(0, macroActivityLog.getDocument().getLength() / 2);
                } catch (Exception ignored) { /* keep going */ }
            }
            if (macroActivityLog.getText().length() > 0) macroActivityLog.append("\n");
            macroActivityLog.append(line);
            macroActivityLog.setCaretPosition(macroActivityLog.getDocument().getLength());
            statusLabel.setText(line.length() > 90 ? line.substring(0, 87) + "…" : line);
        });
    }

    /** Scans the region; returns the absolute screen Point of the first match, or null if none. */
    private Point findPixelLocation(MacroBlock block) {
        int w = Math.max(1, block.searchW);
        int h = Math.max(1, block.searchH);
        if (w > 4000) w = 4000;
        if (h > 4000) h = 4000;
        Rectangle rect = new Rectangle(block.searchX, block.searchY, w, h);
        BufferedImage img;
        try {
            img = robot.createScreenCapture(rect);
        } catch (Exception ex) {
            throw new IllegalStateException("Screen capture failed for if-pixel: " + ex.getMessage(), ex);
        }
        int target = block.colorRgb & 0xFFFFFF;
        int tr = (target >> 16) & 0xFF;
        int tg = (target >> 8) & 0xFF;
        int tb = target & 0xFF;
        int tol = Math.max(0, Math.min(255, block.colorTolerance));
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        for (int py = 0; py < imgH; py++) {
            for (int px = 0; px < imgW; px++) {
                int rgb = img.getRGB(px, py) & 0xFFFFFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (Math.abs(r - tr) <= tol && Math.abs(g - tg) <= tol && Math.abs(b - tb) <= tol) {
                    return new Point(rect.x + px, rect.y + py);
                }
            }
        }
        return null;
    }


/** Returns true when the saved visual reference is found on any attached desktop display. */
private boolean findImageVisible(MacroBlock block) {
    if (block.text == null || block.text.isBlank()) {
        throw new IllegalStateException("Capture a visual reference in the Look for image block first.");
    }
    try {
        Path referencePath = Path.of(block.text);
        if (!Files.isRegularFile(referencePath)) {
            throw new IOException("Visual reference file is missing: " + referencePath.getFileName());
        }
        BufferedImage reference = ImageIO.read(referencePath.toFile());
        if (reference == null) throw new IOException("The visual reference is not a readable image.");
        Rectangle bounds = virtualScreenBounds();
        BufferedImage screen = robot.createScreenCapture(bounds);
        int rw = reference.getWidth();
        int rh = reference.getHeight();
        if (rw < 5 || rh < 5 || rw > screen.getWidth() || rh > screen.getHeight()) return false;

        int centerX = rw / 2;
        int centerY = rh / 2;
        int anchor = reference.getRGB(centerX, centerY);
        final int tolerance = 42;
        for (int y = 0; y <= screen.getHeight() - rh; y += 2) {
            if (Thread.currentThread().isInterrupted()) return false;
            for (int x = 0; x <= screen.getWidth() - rw; x += 2) {
                if (!similarColor(screen.getRGB(x + centerX, y + centerY), anchor, tolerance)) continue;
                boolean matches = true;
                for (int gy = 0; gy <= 4 && matches; gy++) {
                    int sampleY = (rh - 1) * gy / 4;
                    for (int gx = 0; gx <= 4; gx++) {
                        int sampleX = (rw - 1) * gx / 4;
                        if (!similarColor(screen.getRGB(x + sampleX, y + sampleY),
                                reference.getRGB(sampleX, sampleY), tolerance)) {
                            matches = false;
                            break;
                        }
                    }
                }
                if (matches) return true;
            }
        }
        return false;
    } catch (IOException exception) {
        throw new IllegalStateException("Could not scan for the saved visual reference: " + exception.getMessage(), exception);
    }
}

private static boolean similarColor(int left, int right, int tolerance) {
    return Math.abs(((left >> 16) & 0xFF) - ((right >> 16) & 0xFF)) <= tolerance
            && Math.abs(((left >> 8) & 0xFF) - ((right >> 8) & 0xFF)) <= tolerance
            && Math.abs((left & 0xFF) - (right & 0xFF)) <= tolerance;
}

    /** Advances pc past the matching END_IF for the IF_PIXEL / FIND_IMAGE at fromIndex (handles nesting). */
    private static int skipToMatchingEndIf(List<MacroBlock> blocks, int fromIndex) {
        int depth = 1;
        for (int i = fromIndex + 1; i < blocks.size(); i++) {
            MacroBlockType t = blocks.get(i).type;
            if (t == MacroBlockType.FIND_PIXEL || t == MacroBlockType.FIND_IMAGE) depth++;
            else if (t == MacroBlockType.END_IF) {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return blocks.size(); // unpaired — validation should prevent this
    }

    private void runMacro(List<MacroBlock> blocks, int repeatCount, String macroName) {
        try {
            String structureProblem = validateMacroStructure(blocks);
            if (structureProblem != null) {
                SwingUtilities.invokeLater(() -> {
                    if (!closing) statusLabel.setText("Macro block error: " + structureProblem);
                });
                return;
            }
            boolean stopMacro = false;
            int executedBlocks = 0;
            logMacro("── Macro '" + macroName + "' started (" + repeatCount + " run(s)) ──");
            for (int outer = 0; outer < repeatCount && !closing && isMacrosRuntimeEnabled() && !stopMacro; outer++) {
                if (repeatCount > 1) logMacro("Run " + (outer + 1) + " of " + repeatCount);
                Deque<MacroLoopFrame> loopStack = new ArrayDeque<>();
                int pc = 0;
                while (pc < blocks.size() && !closing && !Thread.currentThread().isInterrupted() && isMacrosRuntimeEnabled()) {
                    if (++executedBlocks > MAX_MACRO_EXECUTED_BLOCKS) {
                        stopMacro = true;
                        logMacro("Stopped: reached " + MAX_MACRO_EXECUTED_BLOCKS + " block safety limit");
                        break;
                    }
                    MacroBlock block = blocks.get(pc);
                    switch (block.type) {
                        case KEY -> {
                            int holdMs = resolveMacroNumber(block, block.milliseconds);
                            logMacro("Press key " + keyName(block.code) + " for " + holdMs + " ms");
                            robot.keyPress(block.code);
                            try { if (holdMs > 0) Thread.sleep(holdMs); }
                            finally { robot.keyRelease(block.code); }
                            pc++;
                        }
                        case MOUSE -> {
                            int holdMs = resolveMacroNumber(block, block.milliseconds);
                            logMacro("Click " + mouseStepName(block.code) + " for " + holdMs + " ms");
                            int mask = mouseButtonMask(block.code);
                            robot.mousePress(mask);
                            try { if (holdMs > 0) Thread.sleep(holdMs); }
                            finally { robot.mouseRelease(mask); }
                            pc++;
                        }
                        case WAIT -> {
                            int waitMs = resolveMacroNumber(block, block.milliseconds);
                            logMacro("Wait " + waitMs + " ms");
                            Thread.sleep(waitMs);
                            pc++;
                        }
                        case NOTIFY -> {
                            String msg = block.text.isBlank() ? macroName : block.text;
                            logMacro("Show notice: " + msg);
                            showNotification("Macro message", msg);
                            pc++;
                        }
                        case TYPE_TEXT -> {
                            int interval = resolveMacroNumber(block, block.milliseconds);
                            logMacro("Type text (" + block.text.length() + " chars, " + interval + " ms interval)");
                            typeMacroText(block.text, interval);
                            pc++;
                        }
                        case SCROLL -> {
                            int notches = resolveMacroNumber(block, Math.abs(block.code));
                            int signedNotches = block.code > 0 ? notches : -notches;
                            logMacro("Scroll " + (signedNotches > 0 ? "up " + signedNotches : "down " + (-signedNotches)) + " notch(es)");
                            robot.mouseWheel(-signedNotches);
                            pc++;
                        }
                        case RANDOM_WAIT -> {
                            int maximum = Math.max(block.milliseconds, block.code);
                            int delay = ThreadLocalRandom.current().nextInt(block.milliseconds, maximum + 1);
                            logMacro("Legacy random wait " + delay + " ms");
                            Thread.sleep(delay);
                            pc++;
                        }
                        case REPEAT -> {
                            if (loopStack.size() >= MAX_MACRO_LOOP_DEPTH) {
                                throw new IllegalStateException("Repeat blocks can be nested up to " + MAX_MACRO_LOOP_DEPTH + " levels.");
                            }
                            int times = resolveMacroNumber(block, block.code);
                            logMacro("Repeat enclosed blocks " + times + " time(s)");
                            loopStack.push(new MacroLoopFrame(pc, times));
                            pc++;
                        }
                        case END_REPEAT -> {
                            if (loopStack.isEmpty()) {
                                logMacro("End repeat (no open loop)");
                                pc++;
                            } else {
                                MacroLoopFrame frame = loopStack.peek();
                                frame.remaining--;
                                if (frame.remaining > 0) {
                                    logMacro("End repeat → loop again (" + frame.remaining + " left)");
                                    pc = frame.startIndex + 1;
                                } else {
                                    logMacro("End repeat → loop finished");
                                    loopStack.pop();
                                    pc++;
                                }
                            }
                        }
                        case STOP -> {
                            logMacro("Stop macro");
                            stopMacro = true;
                            pc = blocks.size();
                        }
                        case BEEP -> {
                            logMacro("Play beep");
                            Toolkit.getDefaultToolkit().beep();
                            pc++;
                        }
                        case COPY_TEXT -> {
                            logMacro("Copy text: " + (block.text.isBlank() ? "(empty)" : block.text));
                            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(block.text), null);
                            pc++;
                        }
                        case COMMENT -> {
                            logMacro("Comment: " + (block.text.isBlank() ? "(empty)" : block.text));
                            pc++;
                        }
                        case RANDOM_NUMBER -> throw new IllegalStateException("A random value block must be placed inside a number slot.");
                        case MOVE_MOUSE -> {
                            int x = resolveMacroNumber(block, block.code);
                            int y = resolveMacroNumber(block, block.milliseconds);
                            logMacro("Move mouse to " + x + "," + y);
                            robot.mouseMove(x, y);
                            pc++;
                        }
                        case MOVE_MOUSE_REL -> {
                            Point current = MouseInfo.getPointerInfo().getLocation();
                            int dx = resolveMacroNumber(block, block.code);
                            int dy = resolveMacroNumber(block, block.milliseconds);
                            logMacro("Move mouse by " + dx + "," + dy + " → " + (current.x + dx) + "," + (current.y + dy));
                            robot.mouseMove(current.x + dx, current.y + dy);
                            pc++;
                        }
                        case FIND_PIXEL -> {
                            Point found = findPixelLocation(block);
                            if (found != null) {
                                logMacro(String.format(Locale.ROOT, "Pixel found (%d,%d)  #%06X  region %d,%d %dx%d",
                                        found.x, found.y, block.colorRgb & 0xFFFFFF,
                                        block.searchX, block.searchY, block.searchW, block.searchH));
                                pc++; // enter the body
                            } else {
                                logMacro(String.format(Locale.ROOT, "Pixel not found  #%06X  region %d,%d %dx%d  → skip body",
                                        block.colorRgb & 0xFFFFFF,
                                        block.searchX, block.searchY, block.searchW, block.searchH));
                                pc = skipToMatchingEndIf(blocks, pc);
                            }
                        }
                        case FIND_IMAGE -> {
                            boolean found = findImageVisible(block);
                            if (found) {
                                logMacro("Look for image: visual reference found → run enclosed blocks");
                                pc++;
                            } else {
                                logMacro("Look for image: reference not found → skip enclosed blocks");
                                pc = skipToMatchingEndIf(blocks, pc);
                            }
                        }
                        case END_IF -> {
                            logMacro("End if");
                            pc++;
                        }
                    }
                    if (stopMacro) break;
                }
            }
            final boolean stoppedEarly = stopMacro;
            if (stoppedEarly) logMacro("── Macro '" + macroName + "' stopped ──");
            else logMacro("── Macro '" + macroName + "' completed (" + repeatCount + " run(s)) ──");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logMacro("── Macro interrupted ──");
        } catch (Exception exception) {
            logMacro("ERROR: " + exception.getMessage());
        }
    }

    private static String validateMacroStructure(List<MacroBlock> blocks) {
        int repeatDepth = 0;
        int ifDepth = 0;
        for (int i = 0; i < blocks.size(); i++) {
            MacroBlockType type = blocks.get(i).type;
            if (type == MacroBlockType.REPEAT) {
                repeatDepth++;
                if (repeatDepth > MAX_MACRO_LOOP_DEPTH) return "Repeat blocks can be nested up to " + MAX_MACRO_LOOP_DEPTH + " levels.";
            } else if (type == MacroBlockType.END_REPEAT) {
                repeatDepth--;
                if (repeatDepth < 0) return "End repeat at block " + (i + 1) + " has no matching Repeat block.";
            } else if (type == MacroBlockType.FIND_PIXEL || type == MacroBlockType.FIND_IMAGE) {
                ifDepth++;
                if (ifDepth > MAX_MACRO_LOOP_DEPTH) return "If / Look for image blocks can be nested up to " + MAX_MACRO_LOOP_DEPTH + " levels.";
            } else if (type == MacroBlockType.END_IF) {
                ifDepth--;
                if (ifDepth < 0) return "End if at block " + (i + 1) + " has no matching If pixel found block.";
            }
        }
        if (repeatDepth != 0) return "There are " + repeatDepth + " Repeat block(s) without a matching End repeat.";
        if (ifDepth != 0) return "There are " + ifDepth + " If pixel found block(s) without a matching End if.";
        return null;
    }

    private static int resolveMacroNumber(MacroBlock block, int fixedValue) {
        if (block == null || !block.hasRandomValue()) return fixedValue;
        return ThreadLocalRandom.current().nextInt(block.randomMinimum, block.randomMaximum + 1);
    }

    private void typeMacroText(String text, int intervalMs) throws InterruptedException {
        for (int i = 0; i < text.length(); i++) {
            if (closing || Thread.currentThread().isInterrupted() || !isMacrosRuntimeEnabled()) return;
            char character = text.charAt(i);
            if (character == '\r') continue;
            int keyCode;
            boolean shift = false;
            if (character == '\n') keyCode = KeyEvent.VK_ENTER;
            else if (character == '\t') keyCode = KeyEvent.VK_TAB;
            else if (character == ' ') keyCode = KeyEvent.VK_SPACE;
            else if (Character.isLetter(character)) {
                keyCode = KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(character));
                shift = Character.isUpperCase(character);
            } else if (Character.isDigit(character)) {
                keyCode = KeyEvent.getExtendedKeyCodeForChar(character);
            } else {
                String plain = "1234567890-=[]\\;',./`";
                String shifted = "!@#$%^&*()_+{}|:\"<>?~";
                int plainIndex = plain.indexOf(character);
                int shiftedIndex = shifted.indexOf(character);
                if (plainIndex >= 0) keyCode = KeyEvent.getExtendedKeyCodeForChar(plain.charAt(plainIndex));
                else if (shiftedIndex >= 0) {
                    char base = plain.charAt(shiftedIndex);
                    keyCode = KeyEvent.getExtendedKeyCodeForChar(base);
                    shift = true;
                } else {
                    throw new IllegalArgumentException("Text block cannot type character '" + character + "' with the current ASCII keyboard mapping.");
                }
            }
            if (keyCode == KeyEvent.VK_UNDEFINED) {
                throw new IllegalArgumentException("Text block contains a character unsupported by the current keyboard layout.");
            }
            if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
            try {
                robot.keyPress(keyCode);
                robot.keyRelease(keyCode);
            } finally {
                if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
            }
            if (intervalMs > 0 && i + 1 < text.length()) Thread.sleep(intervalMs);
        }
    }

    private void showNotification(String title, String message) {
        if (!notificationsEnabled || closing) return;
        SwingUtilities.invokeLater(() -> {
            if (!notificationsEnabled || closing) return;
            int duration = notificationDurationMs;
            JWindow window = new JWindow(this);
            window.setFocusableWindowState(false);
            window.setAutoRequestFocus(false);
            window.setAlwaysOnTop(true);
            window.setType(Window.Type.POPUP);

            // A deliberately compact card: text first, then a dedicated 4 px timer track.
            JPanel card = new JPanel(new BorderLayout(0, 4));
            card.setBackground(new Color(250, 252, 255));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(105, 143, 190), 1),
                    new EmptyBorder(6, 9, 5, 9)));

            JLabel titleLabel = new JLabel(limitUtf(title, 48));
            titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            titleLabel.setForeground(new Color(34, 47, 64));
            JLabel messageLabel = new JLabel(limitUtf(message, 42));
            messageLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            messageLabel.setForeground(new Color(82, 94, 111));

            JPanel textPanel = new JPanel();
            textPanel.setOpaque(false);
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
            textPanel.add(titleLabel);
            textPanel.add(Box.createVerticalStrut(1));
            textPanel.add(messageLabel);
            card.add(textPanel, BorderLayout.CENTER);

            NotificationProgressBar progress = new NotificationProgressBar();
            progress.setPreferredSize(new Dimension(1, 4));
            progress.setMinimumSize(new Dimension(1, 4));
            card.add(progress, BorderLayout.SOUTH);

            window.setContentPane(card);
            window.setSize(new Dimension(250, 60));
            window.setMinimumSize(new Dimension(250, 60));
            notificationWindows.add(window);
            positionNotificationWindows();
            window.setVisible(true);
            try { window.setAlwaysOnTop(true); } catch (SecurityException ignored) { }

            long startNanos = System.nanoTime();
            Timer timer = new Timer(20, event -> {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                double remainingFraction = Math.max(0.0, Math.min(1.0, (duration - (double) elapsed) / duration));
                progress.setRemainingFraction(remainingFraction);
                if (remainingFraction <= 0.0) {
                    ((Timer) event.getSource()).stop();
                    notificationTimers.remove(window);
                    notificationWindows.remove(window);
                    window.dispose();
                    positionNotificationWindows();
                }
            });
            notificationTimers.put(window, timer);
            timer.start();
        });
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private void positionNotificationWindows() {
        if (notificationWindows.isEmpty()) return;
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle bounds = configuration.getBounds();
        try {
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            bounds = new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                    Math.max(1, bounds.width - insets.left - insets.right),
                    Math.max(1, bounds.height - insets.top - insets.bottom));
        } catch (RuntimeException ignored) { }

        NotificationPosition position = notificationPosition;
        boolean left = position.isLeft();
        boolean top = position.isTop();
        int x = left ? bounds.x + 14 : bounds.x + bounds.width - 14;
        int cursorY = top ? bounds.y + 14 : bounds.y + bounds.height - 14;
        for (JWindow window : notificationWindows) {
            int windowX = left ? x : x - window.getWidth();
            int windowY = top ? cursorY : cursorY - window.getHeight();
            window.setLocation(windowX, windowY);
            cursorY += (top ? 1 : -1) * (window.getHeight() + 7);
            try { window.setAlwaysOnTop(true); } catch (SecurityException ignored) { }
        }
    }

    private static final class NotificationProgressBar extends JComponent {
        private static final long serialVersionUID = 1L;
        private double remainingFraction = 1.0;

        private NotificationProgressBar() {
            setOpaque(false);
        }

        private void setRemainingFraction(double value) {
            remainingFraction = Math.max(0.0, Math.min(1.0, value));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                if (width <= 0 || height <= 0) return;
                g.setColor(new Color(218, 226, 237));
                g.fillRoundRect(0, 0, width, height, height, height);
                int filled = (int) Math.round(width * remainingFraction);
                if (filled > 0) {
                    g.setColor(new Color(39, 119, 205));
                    g.fillRoundRect(0, 0, Math.max(height, filled), height, height, height);
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static int mouseButtonMask(int nativeButton) {
        // JNativeHook's Windows event numbering follows the native Windows button order:
        // BUTTON1 = left, BUTTON2 = right, BUTTON3 = middle.
        // java.awt.Robot uses AWT order instead: BUTTON1 = left, BUTTON2 = middle, BUTTON3 = right.
        // Translate explicitly so middle and right clicks are never swapped.
        // Buttons 4 and 5 remain the distinct XBUTTON1/XBUTTON2 side buttons.
        return switch (nativeButton) {
            case NativeMouseEvent.BUTTON1 -> InputEvent.BUTTON1_DOWN_MASK;
            case NativeMouseEvent.BUTTON2 -> InputEvent.BUTTON3_DOWN_MASK; // Native right -> AWT right.
            case NativeMouseEvent.BUTTON3 -> InputEvent.BUTTON2_DOWN_MASK; // Native middle -> AWT middle.
            case NativeMouseEvent.BUTTON4, NativeMouseEvent.BUTTON5 -> extendedMouseButtonMask(nativeButton);
            default -> throw new IllegalArgumentException("Unsupported mouse button: " + nativeButton);
        };
    }

    private static int extendedMouseButtonMask(int button) {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.areExtraMouseButtonsEnabled()) {
            throw new IllegalStateException("Java has disabled extended mouse buttons. Enable extra mouse buttons to use Mouse 4 / Mouse 5.");
        }
        int availableButtons = MouseInfo.getNumberOfButtons();
        if (availableButtons < button) {
            throw new IllegalStateException("Windows reports only " + availableButtons
                    + " mouse button(s), so Mouse " + button + " cannot be sent by this Java runtime.");
        }
        return InputEvent.getMaskForButton(button);
    }

    private static boolean isSupportedMouseButton(int button) {
        return button >= NativeMouseEvent.BUTTON1 && button <= NativeMouseEvent.BUTTON5;
    }

    private static String mouseStepName(int button) {
        return switch (button) {
            // JNativeHook on Windows reports right as BUTTON2 and middle as BUTTON3.
            case NativeMouseEvent.BUTTON1 -> "Mouse Left";
            case NativeMouseEvent.BUTTON2 -> "Mouse Right";
            case NativeMouseEvent.BUTTON3 -> "Mouse Middle";
            case NativeMouseEvent.BUTTON4 -> "Mouse 4";
            case NativeMouseEvent.BUTTON5 -> "Mouse 5";
            default -> "Mouse Button " + button + " click";
        };
    }

    private static boolean isModifierKey(int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_META
                || keyCode == KeyEvent.VK_ALT_GRAPH;
    }

    private static int nativeKeyToAwt(int nativeCode) {
        String name = NativeKeyEvent.getKeyText(nativeCode);
        if (name == null || name.isBlank()) return KeyEvent.VK_UNDEFINED;
        String normalized = name.trim();
        String upper = normalized.toUpperCase();

        Map<String, Integer> special = SPECIAL_KEY_CODES;
        Integer specialCode = special.get(upper);
        if (specialCode != null) return specialCode;

        if (normalized.length() == 1) {
            int code = KeyEvent.getExtendedKeyCodeForChar(normalized.charAt(0));
            if (code != KeyEvent.VK_UNDEFINED) return code;
        }
        if (upper.matches("F([1-9]|1[0-2])")) {
            int number = Integer.parseInt(upper.substring(1));
            return KeyEvent.VK_F1 + number - 1;
        }
        return KeyEvent.VK_UNDEFINED;
    }

    private static final Map<String, Integer> SPECIAL_KEY_CODES = createSpecialKeyMap();

    private static Map<String, Integer> createSpecialKeyMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("SPACE", KeyEvent.VK_SPACE);
        map.put("TAB", KeyEvent.VK_TAB);
        map.put("ENTER", KeyEvent.VK_ENTER);
        map.put("RETURN", KeyEvent.VK_ENTER);
        map.put("BACKSPACE", KeyEvent.VK_BACK_SPACE);
        map.put("ESC", KeyEvent.VK_ESCAPE);
        map.put("ESCAPE", KeyEvent.VK_ESCAPE);
        map.put("LEFT", KeyEvent.VK_LEFT);
        map.put("RIGHT", KeyEvent.VK_RIGHT);
        map.put("UP", KeyEvent.VK_UP);
        map.put("DOWN", KeyEvent.VK_DOWN);
        map.put("DELETE", KeyEvent.VK_DELETE);
        map.put("INSERT", KeyEvent.VK_INSERT);
        map.put("HOME", KeyEvent.VK_HOME);
        map.put("END", KeyEvent.VK_END);
        map.put("PAGE UP", KeyEvent.VK_PAGE_UP);
        map.put("PAGE DOWN", KeyEvent.VK_PAGE_DOWN);
        map.put("SHIFT", KeyEvent.VK_SHIFT);
        map.put("CONTROL", KeyEvent.VK_CONTROL);
        map.put("CTRL", KeyEvent.VK_CONTROL);
        map.put("ALT", KeyEvent.VK_ALT);
        map.put("META", KeyEvent.VK_META);
        map.put("ALT GRAPH", KeyEvent.VK_ALT_GRAPH);
        map.put("CAPS LOCK", KeyEvent.VK_CAPS_LOCK);
        map.put("NUM LOCK", KeyEvent.VK_NUM_LOCK);
        map.put("SCROLL LOCK", KeyEvent.VK_SCROLL_LOCK);
        map.put("PRINT SCREEN", KeyEvent.VK_PRINTSCREEN);
        map.put("PAUSE", KeyEvent.VK_PAUSE);
        return Map.copyOf(map);
    }

    private static int mouseTriggerCode(int button) {
        if (!isSupportedMouseButton(button)) {
            throw new IllegalArgumentException("Unsupported mouse trigger button: " + button);
        }
        return -button;
    }

    private static String triggerName(int triggerCode) {
        if (triggerCode >= 0) return keyName(triggerCode);
        return switch (-triggerCode) {
            case NativeMouseEvent.BUTTON1 -> "Mouse Left";
            case NativeMouseEvent.BUTTON2 -> "Mouse Right";
            case NativeMouseEvent.BUTTON3 -> "Mouse Middle";
            case NativeMouseEvent.BUTTON4 -> "Mouse 4";
            case NativeMouseEvent.BUTTON5 -> "Mouse 5";
            default -> "Mouse Button " + (-triggerCode);
        };
    }

    private static String keyName(int keyCode) {
        return KeyEvent.getKeyText(keyCode);
    }

    private void loadBinds() {
        if (!Files.exists(saveFile)) return;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(saveFile)))) {
            int version = input.readInt();
            if (version < 1 || version > FILE_VERSION) {
                throw new IOException("Unsupported save-file version.");
            }
            if (version >= 6) {
                String storedDelayMode = input.readUTF();
                try {
                    // Older releases saved NULL. Migrate it to Per-step so old binds still load.
                    DelayMode loadedMode = "NULL".equalsIgnoreCase(storedDelayMode)
                            ? DelayMode.PER_STEP : DelayMode.valueOf(storedDelayMode);
                    selectDelayMode(loadedMode);
                } catch (IllegalArgumentException invalidMode) {
                    throw new IOException("The saved Delay Mode is not recognized.", invalidMode);
                }
            } else {
                selectDelayMode(DelayMode.PER_STEP);
            }
            int bindCount = input.readInt();
            if (bindCount < 0 || bindCount > MAX_BINDS) throw new IOException("Invalid bind count.");
            for (int i = 0; i < bindCount; i++) {
                int trigger = input.readInt();
                boolean enabled = input.readBoolean();
                int repeatCount = version >= 5 ? input.readInt() : 1;
                if (repeatCount < 1 || repeatCount > MAX_REPEAT_COUNT) {
                    throw new IOException("Invalid sequence repeat count.");
                }
                String displayName = version >= 9 ? input.readUTF() : "";
                if (displayName.length() > 64) throw new IOException("A bind name is too long.");
                Bind bind = new Bind(trigger);
                bind.enabled = enabled;
                bind.repeatCount = repeatCount;
                bind.displayName = displayName;
                if (version >= 10) {
                    int additionalTriggers = input.readInt();
                    if (additionalTriggers < 0 || additionalTriggers >= MAX_TRIGGERS_PER_BIND) throw new IOException("Invalid additional trigger count.");
                    for (int t = 0; t < additionalTriggers; t++) {
                        int additional = input.readInt();
                        if (bind.triggerCodes.contains(additional)) throw new IOException("Duplicate trigger on a Sequence bind.");
                        bind.triggerCodes.add(additional);
                    }
                }
                int stepCount = input.readInt();
                if (stepCount < 0 || stepCount > MAX_STEPS_PER_BIND) throw new IOException("Invalid step count.");
                for (int j = 0; j < stepCount; j++) {
                    int type = input.readUnsignedByte();
                    if (type == 0) {
                        int keyCode = input.readInt();
                        int holdMs = version >= 4 ? input.readInt() : DEFAULT_HOLD_MS;
                        bind.steps.add(Step.key(keyCode, holdMs));
                    } else if (type == 1) {
                        bind.steps.add(Step.waitFor(input.readInt()));
                    } else if (type == 2 && version >= 3) {
                        int button = input.readInt();
                        int holdMs = version >= 4 ? input.readInt() : DEFAULT_HOLD_MS;
                        bind.steps.add(Step.mouseClick(button, holdMs));
                    } else {
                        throw new IOException("Unknown step type.");
                    }
                }
                bindListModel.addElement(bind);
                for (int triggerCode : bind.triggerCodes) {
                    if (triggerMap.containsKey(triggerCode) || holdTriggerMap.containsKey(triggerCode) || macroTriggerMap.containsKey(triggerCode))
                        throw new IOException("A trigger is assigned more than once.");
                    triggerMap.put(triggerCode, bind);
                }
            }
            if (version >= 7) {
                int holdCount = input.readInt();
                if (holdCount < 0 || holdCount > MAX_BINDS) throw new IOException("Invalid hold bind count.");
                for (int i = 0; i < holdCount; i++) {
                    int trigger = input.readInt();
                    boolean enabled = input.readBoolean();
                    int actionType = input.readUnsignedByte();
                    int actionCode = input.readInt();
                    int intervalMs = input.readInt();
                    int pressMs = input.readInt();
                    String displayName = version >= 9 ? input.readUTF() : "";
                    if (displayName.length() > 64) throw new IOException("A Hold bind name is too long.");
                    if (intervalMs < 1 || intervalMs > 10_000 || pressMs < 1 || pressMs > MAX_HOLD_MS) {
                        throw new IOException("Invalid hold timing values.");
                    }
                    if (actionType < 0 || actionType >= HoldActionType.values().length) {
                        throw new IOException("Invalid hold action type.");
                    }
                    if (triggerMap.containsKey(trigger) || holdTriggerMap.containsKey(trigger)) {
                        throw new IOException("A trigger is assigned more than once.");
                    }
                    HoldBind holdBind = new HoldBind(trigger);
                    holdBind.enabled = enabled;
                    holdBind.actionType = HoldActionType.values()[actionType];
                    holdBind.actionCode = actionCode;
                    holdBind.intervalMs = intervalMs;
                    holdBind.pressMs = pressMs;
                    holdBind.displayName = displayName;
                    holdBindListModel.addElement(holdBind);
                    holdTriggerMap.put(trigger, holdBind);
                }
            }
            if (version >= 8) {
                String savedPresetName = input.readUTF();
                input.readUTF(); // Older releases stored a manually selected config path; no longer needed.
                String savedOverrides = input.readUTF();
                if (savedOverrides.length() > 30_000) throw new IOException("Saved Potato Mode settings are too large.");
                selectSavedPotatoPreset(savedPresetName);
            }
            loadingSettings = true;
            try {
                if (version >= 9) {
                    confirmDeleteCheck.setSelected(input.readBoolean());
                    alwaysOnTopCheck.setSelected(input.readBoolean());
                }
                setAlwaysOnTop(alwaysOnTopCheck.isSelected());
            } finally {
                loadingSettings = false;
            }
            if (version >= 10) {
                loadingSettings = true;
                try {
                    notificationsEnabled = input.readBoolean();
                    notificationDurationMs = input.readInt();
                    if (notificationDurationMs < 500 || notificationDurationMs > 15_000) throw new IOException("Invalid notification duration.");
                    if (version >= 11) {
                        try {
                            notificationPosition = NotificationPosition.valueOf(input.readUTF());
                        } catch (IllegalArgumentException invalidPosition) {
                            throw new IOException("Invalid notification position.", invalidPosition);
                        }
                    } else {
                        notificationPosition = NotificationPosition.TOP_RIGHT;
                    }
                    notificationsEnabledCheck.setSelected(notificationsEnabled);
                    notificationDurationSpinner.setValue(notificationDurationMs);
                    notificationPositionCombo.setSelectedItem(notificationPosition);
                } finally {
                    loadingSettings = false;
                }
                int macroCount = input.readInt();
                if (macroCount < 0 || macroCount > MAX_BINDS) throw new IOException("Invalid macro count.");
                for (int i = 0; i < macroCount; i++) {
                    String name = input.readUTF();
                    boolean enabled = input.readBoolean();
                    int repeatCount = input.readInt();
                    int triggerCount = input.readInt();
                    if (name.length() > 64 || repeatCount < 1 || repeatCount > MAX_REPEAT_COUNT
                            || triggerCount < 1 || triggerCount > MAX_TRIGGERS_PER_BIND) throw new IOException("Invalid macro settings.");
                    Macro macro = new Macro(name, 0);
                    macro.triggerCodes.clear();
                    macro.enabled = enabled;
                    macro.repeatCount = repeatCount;
                    for (int t = 0; t < triggerCount; t++) {
                        int trigger = input.readInt();
                        if (triggerMap.containsKey(trigger) || holdTriggerMap.containsKey(trigger) || macroTriggerMap.containsKey(trigger))
                            throw new IOException("A macro trigger is already assigned.");
                        macro.triggerCodes.add(trigger);
                        macroTriggerMap.put(trigger, macro);
                    }
                    int blockCount = input.readInt();
                    if (blockCount < 0 || blockCount > MAX_STEPS_PER_BIND) throw new IOException("Invalid macro block count.");
                    for (int b = 0; b < blockCount; b++) {
                        int type = input.readUnsignedByte();
                        if (type < 0 || type >= MacroBlockType.values().length) throw new IOException("Unknown macro block type.");
                        int code = input.readInt();
                        int milliseconds = input.readInt();
                        String text = input.readUTF();
                        int randomMinimum = version >= 14 ? input.readInt() : 0;
                        int randomMaximum = version >= 14 ? input.readInt() : 0;
                        int searchX = 0, searchY = 0, searchW = 100, searchH = 100, colorRgb = 0, colorTolerance = 12;
                        if (version >= 15) {
                            searchX = input.readInt();
                            searchY = input.readInt();
                            searchW = input.readInt();
                            searchH = input.readInt();
                            colorRgb = input.readInt();
                            colorTolerance = input.readInt();
                        }
                        MacroBlockType blockType = MacroBlockType.values()[type];
                        validateLoadedMacroBlock(blockType, code, milliseconds, text, randomMinimum, randomMaximum);
                        if (blockType == MacroBlockType.RANDOM_NUMBER) {
                            throw new IOException("A random value reporter cannot be stored as a standalone action.");
                        }
                        if (blockType == MacroBlockType.RANDOM_WAIT) {
                            // Migrate the old standalone Random wait action into a reusable random value
                            // attached to a normal Wait block.
                            MacroBlock migrated = MacroBlock.waitFor(milliseconds);
                            migrated.randomMinimum = milliseconds;
                            migrated.randomMaximum = code;
                            macro.blocks.add(migrated);
                        } else {
                            MacroBlock loaded = new MacroBlock(blockType, code, milliseconds, text);
                            loaded.randomMinimum = randomMinimum;
                            loaded.randomMaximum = randomMaximum;
                            if (blockType == MacroBlockType.FIND_PIXEL) {
                                if (searchW < 1 || searchW > 8000 || searchH < 1 || searchH > 8000
                                        || colorTolerance < 0 || colorTolerance > 255) {
                                    throw new IOException("Invalid find-pixel region or tolerance.");
                                }
                                loaded.searchX = searchX;
                                loaded.searchY = searchY;
                                loaded.searchW = searchW;
                                loaded.searchH = searchH;
                                loaded.colorRgb = colorRgb & 0xFFFFFF;
                                loaded.colorTolerance = colorTolerance;
                            }
                            macro.blocks.add(loaded);
                        }
                    }
                    macroListModel.addElement(macro);
                }
                refreshMacroBlocks();
            }
            if (version >= 12) {
                loadingSettings = true;
                try {
                    globalToggleTriggerCode = input.readInt();
                    globalToggleAffectsSequence = input.readBoolean();
                    globalToggleAffectsHold = input.readBoolean();
                    globalToggleAffectsMacros = input.readBoolean();
                    if (version >= 14) {
                        int savedRandomMin = input.readInt();
                        int savedRandomMax = input.readInt();
                        if (savedRandomMin < 1 || savedRandomMax < savedRandomMin || savedRandomMax > MAX_WAIT_MS)
                            throw new IOException("Invalid default random reporter range.");
                        randomReporterMinimum = savedRandomMin;
                        randomReporterMaximum = savedRandomMax;
                        refreshRandomReporterButtonLabel();
                    }
                    globalToggleSequenceCheck.setSelected(globalToggleAffectsSequence);
                    globalToggleHoldCheck.setSelected(globalToggleAffectsHold);
                    globalToggleMacrosCheck.setSelected(globalToggleAffectsMacros);
                    // A corrupt or stale duplicate hotkey should not silently steal a saved bind.
                    if (globalToggleTriggerCode != 0 && triggerIsAssigned(globalToggleTriggerCode)) {
                        globalToggleTriggerCode = 0;
                    }
                    globalToggleDisabled = false;
                    refreshGlobalToggleControls();
                } finally {
                    loadingSettings = false;
                }
            }
            statusLabel.setText("Loaded " + bindListModel.size() + " Sequence bind(s), "
                    + holdBindListModel.size() + " Hold bind(s), and " + macroListModel.size() + " macro(s).");
            refreshHoldControls();
        } catch (IOException | RuntimeException exception) {
            loadingSettings = false;
            statusLabel.setText("Could not load saved binds. A fresh save will be created when you edit binds.");
            JOptionPane.showMessageDialog(this,
                    "The saved bind file could not be read:\n" + exception.getMessage(),
                    "Save file warning", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static boolean supportsRandomValue(MacroBlock block) {
        if (block == null) return false;
        return switch (block.type) {
            case KEY, MOUSE, WAIT, TYPE_TEXT, SCROLL, REPEAT, MOVE_MOUSE, MOVE_MOUSE_REL -> true;
            default -> false;
        };
    }

    private static int maximumRandomValueFor(MacroBlock block) {
        if (block == null) return 0;
        return switch (block.type) {
            case KEY, MOUSE -> MAX_HOLD_MS;
            case WAIT -> MAX_WAIT_MS;
            case TYPE_TEXT -> 1_000;
            case SCROLL -> 100;
            case REPEAT -> MAX_REPEAT_COUNT;
            case MOVE_MOUSE, MOVE_MOUSE_REL -> 10_000;
            default -> 0;
        };
    }

    private static void validateLoadedMacroBlock(MacroBlockType type, int code, int milliseconds,
            String text, int randomMinimum, int randomMaximum) throws IOException {
        if (text.length() > 180) throw new IOException("Macro block text is too long.");
        switch (type) {
            case KEY, MOUSE -> {
                if (milliseconds < 1 || milliseconds > MAX_HOLD_MS) throw new IOException("Invalid macro input hold duration.");
            }
            case WAIT -> {
                if (milliseconds < 1 || milliseconds > MAX_WAIT_MS) throw new IOException("Invalid macro wait duration.");
            }
            case RANDOM_WAIT -> {
                if (milliseconds < 1 || milliseconds > MAX_WAIT_MS || code < milliseconds || code > MAX_WAIT_MS)
                    throw new IOException("Invalid legacy random wait range.");
            }
            case TYPE_TEXT -> {
                if (milliseconds < 1 || milliseconds > 1_000) throw new IOException("Invalid text typing interval.");
            }
            case SCROLL -> {
                if (code == 0 || code < -20 || code > 20) throw new IOException("Invalid scroll amount.");
            }
            case REPEAT -> {
                if (code < 1 || code > MAX_REPEAT_COUNT) throw new IOException("Invalid repeat count.");
            }
            case END_REPEAT, END_IF, FIND_IMAGE, STOP, BEEP, COPY_TEXT, COMMENT, NOTIFY -> { /* no additional numeric values */ }
            case RANDOM_NUMBER -> throw new IOException("A random value reporter cannot be saved as a standalone action.");
            case MOVE_MOUSE -> {
                if (code < -20_000 || code > 20_000 || milliseconds < -20_000 || milliseconds > 20_000)
                    throw new IOException("Invalid absolute mouse coordinates.");
            }
            case MOVE_MOUSE_REL -> {
                if (code < -10_000 || code > 10_000 || milliseconds < -10_000 || milliseconds > 10_000)
                    throw new IOException("Invalid relative mouse delta.");
            }
            case FIND_PIXEL -> {
                // Extra fields (search region / color) are validated after load when version supports them.
            }
        }
        if ((randomMinimum == 0) != (randomMaximum == 0)) {
            throw new IOException("Incomplete random value range.");
        }
        if (randomMinimum != 0) {
            MacroBlock probe = new MacroBlock(type, code, milliseconds, text);
            int maximumAllowed = maximumRandomValueFor(probe);
            if (!supportsRandomValue(probe) || randomMinimum < 1 || randomMaximum < randomMinimum
                    || randomMaximum > maximumAllowed) {
                throw new IOException("Invalid random number range for " + type.label + " input.");
            }
        }
    }

    private void persistBinds() {
        try {
            updatePotatoPresetStorage();
            Files.createDirectories(saveFile.getParent());
            Path temporary = saveFile.resolveSibling("binds.dat.tmp");
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(FILE_VERSION);
                output.writeUTF(delayMode.name());
                output.writeInt(bindListModel.size());
                for (int i = 0; i < bindListModel.size(); i++) {
                    Bind bind = bindListModel.get(i);
                    output.writeInt(bind.triggerKeyCode);
                    output.writeBoolean(bind.enabled);
                    output.writeInt(bind.repeatCount);
                    output.writeUTF(limitUtf(bind.displayName, 64));
                    synchronized (bind.triggerCodes) {
                        output.writeInt(Math.max(0, bind.triggerCodes.size() - 1));
                        for (int t = 1; t < bind.triggerCodes.size(); t++) output.writeInt(bind.triggerCodes.get(t));
                    }
                    synchronized (bind.steps) {
                        output.writeInt(bind.steps.size());
                        for (Step step : bind.steps) {
                            if (step.type == StepType.KEY) {
                                output.writeByte(0);
                                output.writeInt(step.keyCode);
                                output.writeInt(step.holdMs);
                            } else if (step.type == StepType.WAIT) {
                                output.writeByte(1);
                                output.writeInt(step.waitMs);
                            } else {
                                output.writeByte(2);
                                output.writeInt(step.mouseButton);
                                output.writeInt(step.holdMs);
                            }
                        }
                    }
                }
                output.writeInt(holdBindListModel.size());
                for (int i = 0; i < holdBindListModel.size(); i++) {
                    HoldBind bind = holdBindListModel.get(i);
                    output.writeInt(bind.triggerKeyCode);
                    output.writeBoolean(bind.enabled);
                    output.writeByte(bind.actionType.ordinal());
                    output.writeInt(bind.actionCode);
                    output.writeInt(bind.intervalMs);
                    output.writeInt(bind.pressMs);
                    output.writeUTF(limitUtf(bind.displayName, 64));
                }
                output.writeUTF(limitUtf(potatoGameNameField.getText().trim(), 120));
                output.writeUTF(limitUtf(potatoConfigPathField.getText().trim(), 2_000));
                output.writeUTF(limitUtf(potatoOverridesArea.getText(), 15_000));
                output.writeBoolean(confirmDeleteCheck.isSelected());
                output.writeBoolean(alwaysOnTopCheck.isSelected());
                output.writeBoolean(notificationsEnabled);
                output.writeInt(notificationDurationMs);
                output.writeUTF(notificationPosition.name());
                output.writeInt(macroListModel.size());
                for (int i = 0; i < macroListModel.size(); i++) {
                    Macro macro = macroListModel.get(i);
                    output.writeUTF(limitUtf(macro.name, 64));
                    output.writeBoolean(macro.enabled);
                    output.writeInt(macro.repeatCount);
                    synchronized (macro.triggerCodes) {
                        output.writeInt(macro.triggerCodes.size());
                        for (int trigger : macro.triggerCodes) output.writeInt(trigger);
                    }
                    synchronized (macro.blocks) {
                        output.writeInt(macro.blocks.size());
                        for (MacroBlock block : macro.blocks) {
                            output.writeByte(block.type.ordinal());
                            output.writeInt(block.code);
                            output.writeInt(block.milliseconds);
                            output.writeUTF(limitUtf(block.text, 180));
                            output.writeInt(block.randomMinimum);
                            output.writeInt(block.randomMaximum);
                            // Version 15+: find-pixel region and color (zeros for other block types).
                            output.writeInt(block.searchX);
                            output.writeInt(block.searchY);
                            output.writeInt(block.searchW);
                            output.writeInt(block.searchH);
                            output.writeInt(block.colorRgb);
                            output.writeInt(block.colorTolerance);
                        }
                    }
                }
                // Persist the toggle key and group selection, but deliberately not the
                // temporary disabled/enabled state: each fresh app launch starts enabled.
                output.writeInt(globalToggleTriggerCode);
                output.writeBoolean(globalToggleAffectsSequence);
                output.writeBoolean(globalToggleAffectsHold);
                output.writeBoolean(globalToggleAffectsMacros);
                output.writeInt(randomReporterMinimum);
                output.writeInt(randomReporterMaximum);
            }
            try {
                Files.move(temporary, saveFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, saveFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            statusLabel.setText("Could not save binds: " + exception.getMessage());
        }
    }

    private void shutdown() {
        if (closing) return;
        closing = true;
        waitingForTrigger = false;
        capturingSequenceAction = false;
        capturingAdditionalSequenceTrigger = false;
        waitingForHoldTrigger = false;
        capturingHoldAction = false;
        capturingMacroTrigger = false;
        capturingMacroBlockInput = false;
        capturingGlobalToggleKey = false;
        stopAllHoldBinds();
        Future<?> active = activeAutomationFuture;
        if (active != null) active.cancel(true);
        persistBinds();
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.removeNativeMouseListener(this);
            GlobalScreen.removeNativeMouseWheelListener(this);
            if (GlobalScreen.isNativeHookRegistered()) GlobalScreen.unregisterNativeHook();
        } catch (Exception ignored) {
            // Continue closing even if the native hook has already been removed.
        }
        for (Timer timer : new ArrayList<>(notificationTimers.values())) timer.stop();
        notificationTimers.clear();
        for (JWindow window : new ArrayList<>(notificationWindows)) window.dispose();
        notificationWindows.clear();
        macroExecutor.shutdownNow();
        holdExecutor.shutdownNow();
        dispose();
    }

    private static String limitUtf(String value, int maxChars) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars);
    }

    private static final class IniSetting {
        private final String sectionName;
        private final String key;
        private final String value;

        private IniSetting(String sectionName, String key, String value) {
            this.sectionName = sectionName;
            this.key = key;
            this.value = value;
        }
    }

    private static final class Bind {
        private final int triggerKeyCode;
        private final List<Integer> triggerCodes = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<Step> steps = new ArrayList<>();
        private volatile boolean enabled = true;
        private volatile int repeatCount = 1;
        private volatile String displayName = "";

        private Bind(int triggerKeyCode) {
            this.triggerKeyCode = triggerKeyCode;
            this.triggerCodes.add(triggerKeyCode);
        }

        @Override
        public String toString() {
            return bindDisplayName(displayName, triggerCodes);
        }
    }

    private enum MacroBlockType {
        // Keep the first four ordinals stable for existing v1.4.1 macro saves.
        // New types must be appended so old save ordinals remain valid.
        KEY("Press key"), MOUSE("Click mouse"), WAIT("Wait"), NOTIFY("Show notification"),
        TYPE_TEXT("Type text"), SCROLL("Scroll wheel"), RANDOM_WAIT("Random wait"),
        REPEAT("Repeat"), END_REPEAT("End repeat"), STOP("Stop macro"),
        BEEP("Play beep"), COPY_TEXT("Copy text"), COMMENT("Comment"),
        RANDOM_NUMBER("Random number"),
        MOVE_MOUSE("Move mouse to"), MOVE_MOUSE_REL("Move mouse by"),
        FIND_PIXEL("If pixel found"), END_IF("End if"), FIND_IMAGE("If image found");
        private final String label;
        MacroBlockType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final class MacroBlock {
        private MacroBlockType type;
        // Generic numeric fields are interpreted by block type:
        // KEY/MOUSE: code=input, milliseconds=hold; SCROLL: code=signed wheel notches;
        // RANDOM_WAIT: legacy min/max; REPEAT: code=count. A reusable random reporter
        // is stored as an optional operand on a block's numeric input, never as an action.
        private int code;
        private int milliseconds;
        private String text;
        private int randomMinimum;
        private int randomMaximum;
        // FIND_PIXEL: search region + target color (packed 0xRRGGBB) + per-channel tolerance.
        private int searchX;
        private int searchY;
        private int searchW = 100;
        private int searchH = 100;
        private int colorRgb;
        private int colorTolerance = 12;

        private MacroBlock(MacroBlockType type, int code, int milliseconds, String text) {
            this(type, code, milliseconds, text, 0, 0);
        }

        private MacroBlock(MacroBlockType type, int code, int milliseconds, String text,
                int randomMinimum, int randomMaximum) {
            this.type = type;
            this.code = code;
            this.milliseconds = milliseconds;
            this.text = text == null ? "" : text;
            this.randomMinimum = randomMinimum;
            this.randomMaximum = randomMaximum;
        }

        private boolean hasRandomValue() { return randomMinimum >= 1 && randomMaximum >= randomMinimum; }

        private static MacroBlock key(int code, int ms) { return new MacroBlock(MacroBlockType.KEY, code, ms, ""); }
        private static MacroBlock mouse(int button, int ms) { return new MacroBlock(MacroBlockType.MOUSE, button, ms, ""); }
        private static MacroBlock waitFor(int ms) { return new MacroBlock(MacroBlockType.WAIT, 0, ms, ""); }
        private static MacroBlock notify(String text) { return new MacroBlock(MacroBlockType.NOTIFY, 0, 1, text); }
        private static MacroBlock typeText(String text, int intervalMs) { return new MacroBlock(MacroBlockType.TYPE_TEXT, 0, intervalMs, text); }
        private static MacroBlock scroll(int signedNotches) { return new MacroBlock(MacroBlockType.SCROLL, signedNotches, 1, ""); }
        private static MacroBlock randomWait(int min, int max) { return new MacroBlock(MacroBlockType.RANDOM_WAIT, max, min, ""); }
        private static MacroBlock repeat(int count) { return new MacroBlock(MacroBlockType.REPEAT, count, 1, ""); }
        private static MacroBlock marker(MacroBlockType type) { return new MacroBlock(type, 0, 1, ""); }
        private static MacroBlock copyText(String text) { return new MacroBlock(MacroBlockType.COPY_TEXT, 0, 1, text); }
        private static MacroBlock comment(String text) { return new MacroBlock(MacroBlockType.COMMENT, 0, 1, text); }
        private static MacroBlock moveMouse(int x, int y) { return new MacroBlock(MacroBlockType.MOVE_MOUSE, x, y, ""); }
        private static MacroBlock moveMouseRel(int dx, int dy) { return new MacroBlock(MacroBlockType.MOVE_MOUSE_REL, dx, dy, ""); }
        private static MacroBlock findPixel(int x, int y, int w, int h, int rgb, int tolerance) {
            MacroBlock block = new MacroBlock(MacroBlockType.FIND_PIXEL, 0, 0, "");
            block.searchX = x;
            block.searchY = y;
            block.searchW = Math.max(1, w);
            block.searchH = Math.max(1, h);
            block.colorRgb = rgb & 0xFFFFFF;
            block.colorTolerance = Math.max(0, Math.min(255, tolerance));
            return block;
        }
        private static MacroBlock findImage() { return new MacroBlock(MacroBlockType.FIND_IMAGE, 0, 0, ""); }

        private MacroBlock copy() {
            MacroBlock copy = new MacroBlock(type, code, milliseconds, text, randomMinimum, randomMaximum);
            copy.searchX = searchX;
            copy.searchY = searchY;
            copy.searchW = searchW;
            copy.searchH = searchH;
            copy.colorRgb = colorRgb;
            copy.colorTolerance = colorTolerance;
            return copy;
        }

        private String description() {
            return switch (type) {
                case KEY -> "Press " + keyName(code) + " for " + milliseconds + " ms";
                case MOUSE -> "Click " + mouseStepName(code) + " for " + milliseconds + " ms";
                case WAIT -> "Wait " + numericSlotText(this, milliseconds) + " ms";
                case NOTIFY -> "Notify: " + (text.isBlank() ? "(empty)" : text);
                case TYPE_TEXT -> "Type text (" + text.length() + " chars) · " + milliseconds + " ms interval";
                case SCROLL -> "Scroll " + (code > 0 ? "up " + code : "down " + (-code)) + " notch(es)";
                case RANDOM_WAIT -> "Legacy random wait " + milliseconds + "–" + code + " ms";
                case REPEAT -> "Repeat enclosed blocks " + code + " times";
                case END_REPEAT -> "End repeat loop";
                case STOP -> "Stop this macro";
                case BEEP -> "Play system beep";
                case COPY_TEXT -> "Copy text: " + (text.isBlank() ? "(empty)" : text);
                case COMMENT -> "Comment: " + (text.isBlank() ? "(empty)" : text);
                case RANDOM_NUMBER -> "Random number " + milliseconds + "–" + code;
                case MOVE_MOUSE -> "Move mouse to x:" + numericSlotText(this, code) + " y:" + numericSlotText(this, milliseconds);
                case MOVE_MOUSE_REL -> "Move mouse by dx:" + numericSlotText(this, code) + " dy:" + numericSlotText(this, milliseconds);
                case FIND_PIXEL -> String.format(Locale.ROOT,
                        "If #%06X found in %d,%d %dx%d (±%d)",
                        colorRgb & 0xFFFFFF, searchX, searchY, searchW, searchH, colorTolerance);
                case FIND_IMAGE -> "Look for image: " + (text.isBlank() ? "reference not set" : text);
                case END_IF -> "End if (close If / Look for image)";
            };
        }
    }

    private static final class MacroLoopFrame {
        private final int startIndex;
        private int remaining;
        private MacroLoopFrame(int startIndex, int remaining) {
            this.startIndex = startIndex;
            this.remaining = remaining;
        }
    }

    private static final class Macro {
        private String name;
        private final List<Integer> triggerCodes = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<MacroBlock> blocks = java.util.Collections.synchronizedList(new ArrayList<>());
        private boolean enabled = true;
        private int repeatCount = 1;

        private Macro(String name, int triggerCode) {
            this.name = name == null || name.isBlank() ? "Macro" : name;
            if (triggerCode != 0) triggerCodes.add(triggerCode);
        }
        @Override public String toString() { return name + "  " + triggerSummary(triggerCodes); }
    }

    private enum HoldActionType { NONE, KEY, MOUSE }

    private static final class HoldBind {
        private final int triggerKeyCode;
        private volatile boolean enabled = true;
        private volatile HoldActionType actionType = HoldActionType.NONE;
        private volatile int actionCode;
        private volatile int intervalMs = 50;
        private volatile int pressMs = DEFAULT_HOLD_MS;
        private volatile String displayName = "";
        private volatile boolean outputDown;
        private volatile ScheduledFuture<?> repeatTask;

        private HoldBind(int triggerKeyCode) {
            this.triggerKeyCode = triggerKeyCode;
        }

        @Override
        public String toString() {
            return bindDisplayName(displayName, triggerKeyCode);
        }
    }

    private static String holdActionName(HoldBind bind) {
        return switch (bind.actionType) {
            case NONE -> "Not set";
            case KEY -> "Key " + keyName(bind.actionCode);
            case MOUSE -> mouseStepName(bind.actionCode);
        };
    }

    private static final class HoldBindRenderer extends JPanel implements ListCellRenderer<HoldBind> {
        private static final long serialVersionUID = 1L;
        private final JLabel title = new JLabel();
        private final JLabel trigger = new JLabel();
        private final JLabel details = new JLabel();

        private HoldBindRenderer() {
            super(new BorderLayout(10, 0));
            setOpaque(true);
            JPanel labels = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
            labels.setOpaque(false);
            title.setFont(new Font("Segoe UI", Font.BOLD, 13));
            trigger.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            labels.add(title);
            labels.add(trigger);
            details.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            add(labels, BorderLayout.WEST);
            add(details, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends HoldBind> list, HoldBind bind,
                                                      int index, boolean selected, boolean focused) {
            Color foreground = selected ? list.getSelectionForeground() : new Color(42, 51, 64);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            title.setForeground(foreground);
            trigger.setForeground(new Color(91, 101, 116));
            trigger.setOpaque(true);
            trigger.setBackground(new Color(232, 235, 240));
            trigger.setBorder(new EmptyBorder(2, 5, 2, 5));
            details.setForeground(new Color(82, 93, 108));
            title.setText(bindDisplayName(bind.displayName, bind.triggerKeyCode));
            trigger.setText(bind.displayName == null || bind.displayName.isBlank()
                    ? "" : "[" + triggerName(bind.triggerKeyCode) + "]");
            details.setText("→ " + holdActionName(bind) + "  ·  every " + bind.intervalMs
                    + " ms  ·  press " + bind.pressMs + " ms  ·  " + (bind.enabled ? "Enabled" : "Disabled"));
            setBorder(new EmptyBorder(5, 8, 5, 8));
            return this;
        }
    }

    private static final class MacroRenderer extends JPanel implements ListCellRenderer<Macro> {
        private static final long serialVersionUID = 1L;
        private final JLabel title = new JLabel();
        private final JLabel trigger = new JLabel();
        private final JLabel details = new JLabel();
        private MacroRenderer() {
            super(new BorderLayout(8, 0));
            setOpaque(true);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
            left.setOpaque(false);
            title.setFont(new Font("Segoe UI", Font.BOLD, 13));
            trigger.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            left.add(title); left.add(trigger);
            details.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            add(left, BorderLayout.WEST); add(details, BorderLayout.EAST);
        }
        @Override public Component getListCellRendererComponent(JList<? extends Macro> list, Macro macro,
                int index, boolean selected, boolean focused) {
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            title.setForeground(selected ? list.getSelectionForeground() : new Color(42, 51, 64));
            trigger.setForeground(new Color(91, 101, 116));
            trigger.setOpaque(true);
            trigger.setBackground(new Color(232, 235, 240));
            trigger.setBorder(new EmptyBorder(2, 5, 2, 5));
            details.setForeground(new Color(82, 93, 108));
            title.setText(macro.name);
            trigger.setText(triggerSummary(macro.triggerCodes));
            details.setText(macro.blocks.size() + " blocks  ·  Repeat ×" + macro.repeatCount + "  ·  " + (macro.enabled ? "Enabled" : "Disabled"));
            setBorder(new EmptyBorder(5, 8, 5, 8));
            return this;
        }
    }

    private static String previewBlockText(String text) {
        String value = text == null || text.isBlank() ? "(empty)" : text;
        return "[ " + (value.length() > 44 ? value.substring(0, 41) + "..." : value) + " ]";
    }

    private static String numericSlotText(MacroBlock block, int fixedValue) {
        return block != null && block.hasRandomValue()
                ? "Random " + block.randomMinimum + "-" + block.randomMaximum
                : Integer.toString(fixedValue);
    }

    private static void drawMacroDetail(Graphics2D g, String text, int x, int baseline,
                                        int maxWidth, Color textColor) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return;
        FontMetrics metrics = g.getFontMetrics();
        int cursor = x;
        int pos = 0;
        while (pos < text.length() && cursor - x < maxWidth) {
            int open = text.indexOf('[', pos);
            if (open < 0) open = text.length();
            if (open > pos) {
                String plain = text.substring(pos, open);
                int remaining = maxWidth - (cursor - x);
                String draw = plain;
                while (!draw.isEmpty() && metrics.stringWidth(draw) > remaining) {
                    draw = draw.substring(0, draw.length() - 1);
                }
                if (!draw.isEmpty()) {
                    g.setColor(textColor);
                    g.drawString(draw, cursor, baseline);
                    cursor += metrics.stringWidth(draw);
                }
                if (draw.length() < plain.length()) break;
                pos = open;
            }
            if (pos >= text.length()) break;
            int close = text.indexOf(']', pos + 1);
            if (close < 0) {
                String tail = text.substring(pos);
                int remaining = maxWidth - (cursor - x);
                String draw = tail;
                while (!draw.isEmpty() && metrics.stringWidth(draw) > remaining) {
                    draw = draw.substring(0, draw.length() - 1);
                }
                if (!draw.isEmpty()) {
                    g.setColor(textColor);
                    g.drawString(draw, cursor, baseline);
                }
                break;
            }
            String slot = text.substring(pos, close + 1);
            int slotWidth = metrics.stringWidth(slot) + 8;
            if (cursor - x + slotWidth > maxWidth) break;
            int slotHeight = Math.max(14, metrics.getHeight() - 1);
            int slotY = baseline - metrics.getAscent() + 1;
            g.setColor(new Color(226, 231, 237));
            g.fillRoundRect(cursor, slotY, slotWidth, slotHeight, 6, 6);
            g.setColor(new Color(78, 89, 105));
            g.drawString(slot, cursor + 4, baseline);
            cursor += slotWidth + 3;
            pos = close + 1;
        }
    }

    private static final class MacroBlockRenderer extends JPanel implements ListCellRenderer<MacroBlock> {
        private static final long serialVersionUID = 1L;
        private Color blockColor = new Color(76, 151, 255);
        private String orderText = "01";
        private String titleText = "PRESS KEY";
        private String detailText = "[ A ]  hold [ 20 ] ms";
        private boolean selected;
        private int indent;

        private MacroBlockRenderer() {
            setOpaque(false);
            setPreferredSize(new Dimension(360, 54));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MacroBlock> list, MacroBlock block,
                int index, boolean isSelected, boolean focused) {
            blockColor = switch (block.type) {
                case KEY -> new Color(76, 151, 255);
                case MOUSE, SCROLL, MOVE_MOUSE, MOVE_MOUSE_REL -> new Color(76, 151, 255);
                case WAIT, RANDOM_WAIT, REPEAT, END_REPEAT -> new Color(255, 171, 25);
                case NOTIFY -> new Color(153, 102, 255);
                case BEEP -> new Color(207, 99, 207);
                case TYPE_TEXT -> new Color(49, 132, 208);
                case STOP -> new Color(211, 76, 76);
                case COPY_TEXT, FIND_PIXEL, FIND_IMAGE, END_IF -> new Color(92, 177, 214);
                case COMMENT -> new Color(119, 130, 145);
                case RANDOM_NUMBER -> new Color(89, 192, 89);
            };
            selected = isSelected;
            int depth = 0;
            for (int i = 0; i < index; i++) {
                MacroBlock priorBlock = list.getModel().getElementAt(i);
                MacroBlockType prior = priorBlock.type;
                if (prior == MacroBlockType.REPEAT || prior == MacroBlockType.FIND_PIXEL || prior == MacroBlockType.FIND_IMAGE) depth++;
                else if (prior == MacroBlockType.END_REPEAT || prior == MacroBlockType.END_IF) depth = Math.max(0, depth - 1);
            }
            if (block.type == MacroBlockType.END_REPEAT || block.type == MacroBlockType.END_IF) {
                depth = Math.max(0, depth - 1);
            }
            indent = Math.min(60, depth * 12);
            orderText = String.format(Locale.ROOT, "%02d", index + 1);
            switch (block.type) {
                case KEY -> {
                    titleText = "PRESS KEY";
                    detailText = "[ " + keyName(block.code) + " ]  hold [ " + numericSlotText(block, block.milliseconds) + " ] ms";
                }
                case MOUSE -> {
                    titleText = "CLICK MOUSE";
                    detailText = "[ " + mouseStepName(block.code) + " ]  hold [ " + numericSlotText(block, block.milliseconds) + " ] ms";
                }
                case WAIT -> {
                    titleText = "WAIT";
                    detailText = "Wait [ " + numericSlotText(block, block.milliseconds) + " ] ms";
                }
                case NOTIFY -> {
                    titleText = "SHOW NOTIFICATION";
                    detailText = previewBlockText(block.text);
                }
                case TYPE_TEXT -> {
                    titleText = "TYPE TEXT";
                    detailText = previewBlockText(block.text) + "  ·  interval [ " + numericSlotText(block, block.milliseconds) + " ] ms";
                }
                case SCROLL -> {
                    titleText = "SCROLL WHEEL";
                    detailText = (block.code > 0 ? "UP " : "DOWN ")
                            + "[ " + numericSlotText(block, Math.abs(block.code)) + " ] notch(es)";
                }
                case RANDOM_WAIT -> {
                    titleText = "RANDOM WAIT";
                    detailText = block.milliseconds + "–" + block.code + " ms";
                }
                case REPEAT -> {
                    titleText = "REPEAT";
                    detailText = "repeat [ " + numericSlotText(block, block.code) + " ] times  ·  body below";
                }
                case END_REPEAT -> {
                    titleText = "END REPEAT";
                    detailText = "closes the Repeat block above";
                }
                case STOP -> {
                    titleText = "STOP MACRO";
                    detailText = "stop running this macro";
                }
                case BEEP -> {
                    titleText = "PLAY BEEP";
                    detailText = "play the system notification sound";
                }
                case COPY_TEXT -> {
                    titleText = "COPY TEXT";
                    detailText = previewBlockText(block.text);
                }
                case COMMENT -> {
                    titleText = "COMMENT";
                    detailText = previewBlockText(block.text);
                }
                case RANDOM_NUMBER -> {
                    titleText = "RANDOM NUMBER VALUE";
                    detailText = "drag this reporter into a numbered [ ] input slot";
                }
                case MOVE_MOUSE -> {
                    titleText = "MOVE MOUSE TO";
                    detailText = "x: [ " + numericSlotText(block, block.code) + " ]  y: [ " + numericSlotText(block, block.milliseconds) + " ]";
                }
                case MOVE_MOUSE_REL -> {
                    titleText = "MOVE MOUSE BY";
                    detailText = "dx: [ " + numericSlotText(block, block.code) + " ]  dy: [ " + numericSlotText(block, block.milliseconds) + " ]";
                }
                case FIND_PIXEL -> {
                    titleText = "IF PIXEL FOUND";
                    detailText = String.format(Locale.ROOT, "#%06X  region %d,%d  %dx%d  tol %d",
                            block.colorRgb & 0xFFFFFF, block.searchX, block.searchY, block.searchW, block.searchH, block.colorTolerance);
                }
                case FIND_IMAGE -> {
                    titleText = "LOOK FOR IMAGE";
                    detailText = block.text.isBlank() ? "capture a small desktop UI reference" : "saved reference: " + Path.of(block.text).getFileName();
                }
                case END_IF -> {
                    titleText = "END IF";
                    detailText = "closes the If pixel found / Look for image block";
                }
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float left = indent + 2f;
                float right = getWidth() - 5f;
                float top = 1f;
                float bottom = getHeight() - 8f;
                float connectorX = left + 28f;
                float half = 8f;
                float socketDepth = 7f;
                Path2D.Float shape = new Path2D.Float();
                shape.moveTo(left + 7f, top);
                shape.lineTo(connectorX - half, top);
                // Top socket: a compact Scratch-style indentation.
                shape.lineTo(connectorX - half + 2f, top + 2f);
                shape.quadTo(connectorX, top + socketDepth, connectorX + half - 2f, top + 2f);
                shape.lineTo(connectorX + half, top);
                shape.lineTo(right - 7f, top);
                shape.quadTo(right, top, right, top + 7f);
                shape.lineTo(right, bottom - 7f);
                shape.quadTo(right, bottom, right - 7f, bottom);
                shape.lineTo(connectorX + half, bottom);
                // Bottom tab reaches the cell edge so adjacent blocks read as one stack.
                shape.lineTo(connectorX + half - 2f, bottom + 2f);
                shape.quadTo(connectorX, bottom + socketDepth, connectorX - half + 2f, bottom + 2f);
                shape.lineTo(connectorX - half, bottom);
                shape.lineTo(left + 7f, bottom);
                shape.quadTo(left, bottom, left, bottom - 7f);
                shape.lineTo(left, top + 7f);
                shape.quadTo(left, top, left + 7f, top);
                shape.closePath();

                Color fill = selected ? blockColor.darker() : blockColor;
                g.setColor(new Color(0, 0, 0, 22));
                g.translate(0, 1);
                g.fill(shape);
                g.translate(0, -1);
                g.setColor(fill);
                g.fill(shape);
                g.setStroke(new BasicStroke(selected ? 2f : 1f));
                g.setColor(selected ? new Color(35, 73, 125) : fill.darker());
                g.draw(shape);

                // A subtle center connector makes adjacent rows read as a connected stack.
                g.setColor(new Color(0, 0, 0, 38));
                g.fillRoundRect(Math.round(connectorX - 3f), Math.round(top + 5f), 6, Math.max(4, getHeight() - 14), 6, 6);

                g.setColor(new Color(0, 0, 0, 64));
                g.fillRoundRect(Math.round(left + 7f), 16, 24, 19, 6, 6);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Segoe UI", Font.BOLD, 9));
                FontMetrics numberMetrics = g.getFontMetrics();
                g.drawString(orderText, Math.round(left + 19f) - numberMetrics.stringWidth(orderText) / 2, 29);

                int textX = Math.round(indent + 43f);
                g.setFont(new Font("Segoe UI", Font.BOLD, 11));
                Color textColor = contrastTextColor(fill);
                g.setColor(textColor);
                g.drawString(titleText, textX, 21);
                g.setFont(new Font("Segoe UI", Font.PLAIN, 10));
                drawMacroDetail(g, detailText, textX, 39, Math.max(0, getWidth() - textX - 12), textColor);
            } finally {
                g.dispose();
            }
        }
    }

    private enum PotatoPreset {
        FORTNITE_LOW("Fortnite - Low", "A low-but-readable preset: turns off motion blur, VSync and ray tracing, and uses the lowest quality groups while keeping full 3D resolution.", 100, 0),
        FORTNITE_POTATO("Fortnite - Potato", "A stronger low-graphics preset: minimum quality groups and 75% 3D resolution for a lighter GPU load.", 75, 0),
        FORTNITE_EXTREME("Fortnite - Extreme Potato", "The most aggressive preset: minimum quality groups and 55% 3D resolution. The picture will look noticeably blurrier.", 55, 0);

        private final String label;
        private final String description;
        private final int resolutionQuality;
        private final int textureQuality;

        PotatoPreset(String label, String description, int resolutionQuality, int textureQuality) {
            this.label = label;
            this.description = description;
            this.resolutionQuality = resolutionQuality;
            this.textureQuality = textureQuality;
        }

        private String iniOverrides() {
            return "[/Script/FortniteGame.FortGameUserSettings]\n"
                    + "bMotionBlur=False\n"
                    + "bShowGrass=False\n"
                    + "bUseVSync=False\n"
                    + "bRayTracing=False\n"
                    + "bUseNanite=False\n"
                    + "FortAntiAliasingMethod=Disabled\n"
                    + "DesiredGlobalIlluminationQuality=0\n"
                    + "DesiredReflectionQuality=0\n"
                    + "PreNaniteGlobalIlluminationQuality=0\n"
                    + "PreNaniteReflectionQuality=0\n\n"
                    + "[ScalabilityGroups]\n"
                    + "sg.ResolutionQuality=" + resolutionQuality + ".000000\n"
                    + "sg.ViewDistanceQuality=0\n"
                    + "sg.AntiAliasingQuality=0\n"
                    + "sg.ShadowQuality=0\n"
                    + "sg.GlobalIlluminationQuality=0\n"
                    + "sg.ReflectionQuality=0\n"
                    + "sg.PostProcessQuality=0\n"
                    + "sg.TextureQuality=" + textureQuality + "\n"
                    + "sg.EffectsQuality=0\n"
                    + "sg.FoliageQuality=0\n"
                    + "sg.ShadingQuality=0\n"
                    + "sg.LandscapeQuality=0\n\n"
                    + "[RayTracing]\n"
                    + "r.RayTracing.EnableInGame=False\n\n"
                    + "[PerformanceMode]\n"
                    + "MeshQuality=0\n";
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum NotificationPosition {
        TOP_LEFT("Top left", true, true),
        TOP_RIGHT("Top right", false, true),
        BOTTOM_LEFT("Bottom left", true, false),
        BOTTOM_RIGHT("Bottom right", false, false);

        private final String label;
        private final boolean left;
        private final boolean top;

        NotificationPosition(String label, boolean left, boolean top) {
            this.label = label;
            this.left = left;
            this.top = top;
        }

        private boolean isLeft() { return left; }
        private boolean isTop() { return top; }

        @Override
        public String toString() { return label; }
    }

    private enum DelayMode {
        PER_STEP("Per-step", -1, "Uses the milliseconds saved on each action."),
        ONE_MS("1 ms", 1, "Uses a 1 ms key/mouse hold."),
        FIVE_MS("5 ms", 5, "Uses a 5 ms key/mouse hold."),
        TEN_MS("10 ms", 10, "Uses a 10 ms key/mouse hold."),
        TWENTY_MS("20 ms", 20, "Uses a 20 ms key/mouse hold."),
        FIFTY_MS("50 ms", 50, "Uses a 50 ms key/mouse hold.");

        private final String label;
        private final int forcedHoldMs;
        private final String description;

        DelayMode(String label, int forcedHoldMs, String description) {
            this.label = label;
            this.forcedHoldMs = forcedHoldMs;
            this.description = description;
        }

        private int resolveHoldMs(int stepHoldMs) {
            return forcedHoldMs < 0 ? stepHoldMs : forcedHoldMs;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum AutomationCategory { SEQUENCE, MACRO }

    private enum StepType { KEY, MOUSE, WAIT }

    private static final class Step {
        private final StepType type;
        private final int keyCode;
        private final int mouseButton;
        private final int holdMs;
        private final int waitMs;

        private Step(StepType type, int keyCode, int mouseButton, int holdMs, int waitMs) {
            this.type = type;
            this.keyCode = keyCode;
            this.mouseButton = mouseButton;
            this.holdMs = holdMs;
            this.waitMs = waitMs;
        }

        private static Step key(int keyCode) {
            return key(keyCode, DEFAULT_HOLD_MS);
        }

        private static Step key(int keyCode, int holdMs) {
            return new Step(StepType.KEY, keyCode, 0,
                    Math.max(1, Math.min(MAX_HOLD_MS, holdMs)), 0);
        }

        private static Step mouseClick(int button) {
            return mouseClick(button, DEFAULT_HOLD_MS);
        }

        private static Step mouseClick(int button, int holdMs) {
            return new Step(StepType.MOUSE, 0, Math.max(1, Math.min(5, button)),
                    Math.max(1, Math.min(MAX_HOLD_MS, holdMs)), 0);
        }

        private static Step waitFor(int milliseconds) {
            return new Step(StepType.WAIT, 0, 0, 0,
                    Math.max(1, Math.min(MAX_WAIT_MS, milliseconds)));
        }
    }

    private static final class BindRenderer extends JPanel implements ListCellRenderer<Bind> {
        private static final long serialVersionUID = 1L;
        private final JLabel title = new JLabel();
        private final JLabel trigger = new JLabel();
        private final JLabel details = new JLabel();

        private BindRenderer() {
            super(new BorderLayout(10, 0));
            setOpaque(true);
            JPanel labels = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
            labels.setOpaque(false);
            title.setFont(new Font("Segoe UI", Font.BOLD, 13));
            trigger.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            labels.add(title);
            labels.add(trigger);
            details.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            add(labels, BorderLayout.WEST);
            add(details, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Bind> list, Bind bind,
                                                      int index, boolean selected, boolean focused) {
            Color foreground = selected ? list.getSelectionForeground() : new Color(42, 51, 64);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            title.setForeground(foreground);
            trigger.setForeground(new Color(91, 101, 116));
            trigger.setOpaque(true);
            trigger.setBackground(new Color(232, 235, 240));
            trigger.setBorder(new EmptyBorder(2, 5, 2, 5));
            details.setForeground(new Color(82, 93, 108));
            title.setText(bindDisplayName(bind.displayName, bind.triggerCodes));
            trigger.setText(bind.displayName == null || bind.displayName.isBlank()
                    ? "" : triggerSummary(bind.triggerCodes));
            details.setText(bind.steps.size() + " steps  ·  Repeat ×" + bind.repeatCount
                    + "  ·  " + (bind.enabled ? "Enabled" : "Disabled"));
            setBorder(new EmptyBorder(5, 8, 5, 8));
            return this;
        }
    }

    private static final class StepRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            Step step = (Step) value;
            String title = switch (step.type) {
                case KEY -> "Key: " + keyName(step.keyCode) + "    (hold " + step.holdMs + " ms)";
                case MOUSE -> "Mouse: " + mouseStepName(step.mouseButton) + "    (hold " + step.holdMs + " ms)";
                case WAIT -> "Wait: " + step.waitMs + " ms";
            };
            setText((index + 1) + ".  " + title);
            setBorder(new EmptyBorder(4, 8, 4, 8));
            return this;
        }
    }




}
