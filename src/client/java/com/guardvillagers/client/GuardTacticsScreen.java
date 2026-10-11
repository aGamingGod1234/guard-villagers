package com.guardvillagers.client;

import com.guardvillagers.network.GuardGroupResultPayload;
import com.guardvillagers.tactics.GuardTacticsScreenHandler;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;

/**
 * "Command post" tactics screen: chunk zone map on the left, a collapsible groups roster on the right and a
 * command bar (zone tools + orders) along the bottom. One screen, no view switching.
 */
public final class GuardTacticsScreen extends AbstractContainerScreen<GuardTacticsScreenHandler> {
	private static final String KEY = "gui.guardvillagers.tactics.";

	private static final int PANEL_BACKGROUND = 0xF1161C25;
	private static final int PANEL_BORDER = 0xFF3B4A5E;
	private static final int TEXT_PRIMARY = 0xFFEAF1FA;
	private static final int TEXT_SECONDARY = 0xFFB6C6D6;
	private static final int TEXT_MUTED = 0xFF7D8DA0;
	private static final int ACCENT_ZONES = 0xFF8FD2FF;
	private static final int ACCENT_GROUPS = 0xFFFFCB90;
	private static final int KEY_COLOR = 0xFFFFD166;
	private static final int STATUS_SUCCESS = 0xFF6FD49A;
	private static final int STATUS_ERROR = 0xFFFF7A7A;
	private static final int STATUS_WARNING = 0xFFFFD36B;
	private static final int SIDEBAR_BACKGROUND = 0xB30E131A;
	private static final int BAR_BACKGROUND = 0xE6131B25;
	private static final int CHIP_BACKGROUND = 0xD10E131A;
	private static final int HEADER_ROW_BACKGROUND = 0xFF1B2531;
	private static final int HEADER_ROW_OPEN_BACKGROUND = 0xFF24324A;
	private static final int HEADER_ROW_HOVER_BACKGROUND = 0xFF2B3B55;
	private static final int ROW_BORDER = 0xFF334154;
	private static final int CARD_BACKGROUND = 0xF217212D;
	private static final int CARD_HOVER_BACKGROUND = 0xF2213044;
	private static final int CARD_HOVER_BORDER = 0xFF8FD2FF;
	private static final int DROP_IDLE_BORDER = 0xFF6A5A30;
	private static final int DROP_HOVER_BORDER = 0xFFFFD166;
	private static final int DROP_HOVER_BACKGROUND = 0x33FFD166;
	private static final int DROP_ZONE_BACKGROUND = 0x18FFD166;
	private static final int SCROLLBAR_TRACK_COLOR = 0x55394656;
	private static final int PENDING_MARKER = 0xFFFFD36B;
	private static final int RANK_COMMANDER_COLOR = 0xFFFFC83D;
	private static final int RANK_CAPTAIN_COLOR = 0xFFC9D3DE;
	private static final int RANK_SHADOW = 0xFF10161E;
	private static final int LEVEL_COLOR = 0xFFD9C27A;
	private static final int RANK_BADGE_SIZE = 5;
	// 5x5 pixel art, one string per row: a star for the commander, a chevron for captains.
	private static final String[] STAR_PIXELS = {"..#..", "#####", ".###.", ".#.#.", "#...#"};
	private static final String[] CHEVRON_PIXELS = {"..#..", ".###.", "##.##", "#...#", "....."};
	private static final int NO_COLOR_STRIPE = 0xFF4A5566;

	// Button styles (fill, hover fill, border)
	private static final int[] STYLE_DARK = {0xFF2A3443, 0xFF3A4A60, 0xFF43546B};
	private static final int[] STYLE_GREY = {0xFF5C5C5C, 0xFF6C76A8, 0xFF9A9A9A};
	private static final int[] STYLE_WARN = {0xFF8A6A25, 0xFF9E7C30, 0xFFD6B15C};
	private static final int[] STYLE_ON = {0xFF3E7A3E, 0xFF4A8C4A, 0xFF77C277};
	private static final int DISABLED_FILL = 0xFF222B36;
	private static final int DISABLED_BORDER = 0xFF3A4654;

	private static final int HEADER_HEIGHT = 16;
	private static final int BAR_HEIGHT = 33;
	private static final int BUTTON_HEIGHT = 12;
	private static final int SMALL_BUTTON_HEIGHT = 11;
	private static final int SWATCH_SIZE = 10;
	private static final int SWATCH_STRIDE = 14;
	private static final int SIDEBAR_MIN_WIDTH = 120;
	private static final int SIDEBAR_MAX_WIDTH = 210;
	private static final int MAP_MIN_WIDTH = 120;
	private static final int SCROLLBAR_SPACE = 5;
	private static final int GROUP_HEADER_HEIGHT = 13;
	private static final int MEMBER_ROW_HEIGHT = 14;
	private static final int MEMBER_ROW_STRIDE = 15;
	private static final int CHIP_WIDTH = 36;
	private static final int CHIP_HEIGHT = 11;
	private static final int CHIP_GAP = 2;
	// Tray chips stretch to fill the row; this is the narrowest they get before wrapping to fewer per row.
	private static final int TRAY_CHIP_MIN_WIDTH = 52;
	private static final int TRAY_CHIP_HEIGHT = 13;
	private static final int GROUP_GAP = 3;
	private static final int WHEEL_SCROLL_PIXELS = 20;
	private static final int DRAG_AUTOSCROLL_EDGE = 14;
	private static final int DRAG_AUTOSCROLL_SPEED = 5;
	private static final double PANE_SCROLL_LERP_FACTOR = 0.35D;
	private static final int KEY_PAN_PIXELS = 48;
	private static final long STATUS_DURATION_MILLIS = 3_500L;
	private static final long SAVE_CONFIRM_TIMEOUT_MILLIS = 5_000L;
	private static final long CHUNK_TOOLTIP_DELAY_MILLIS = 350L;
	private static final int DIALOG_MAX_WIDTH = 260;
	private static final int DIALOG_HEIGHT = 84;
	private static final int DIALOG_BUTTON_HEIGHT = 16;
	private static final int MAX_COMMAND_LENGTH = 256;
	// Mirrors the server's MAX_BATCH_ASSIGN for assignmany.
	private static final int MAX_UUIDS_PER_COMMAND = 1_024;
	private static final int MAX_BAR_SWATCHES = 10;
	private static final Pattern GENERIC_GROUP_NAME = Pattern.compile("Role(\\s+\\d+)?");

	private static final ItemStack SWORD_ICON = new ItemStack(Items.IRON_SWORD);
	private static final ItemStack BOW_ICON = new ItemStack(Items.BOW);

	private static final Component TITLE = tr("title");
	private static final Component HELP_LABEL = tr("help.button");
	private static final Component CLOSE_LABEL = tr("close");
	private static final Component REVERT = tr("revert");
	private static final Component SAVED = tr("saved");
	private static final Component SAVING = tr("saving");
	private static final Component GROUPS_HEADER = tr("groups.header");
	private static final Component ADD_GROUP = tr("add_group");
	private static final Component ZONE_LABEL = tr("bar.zone");
	private static final Component ORDERS_LABEL = tr("bar.orders");
	private static final Component PALETTE_MORE = Component.literal("+");
	private static final Component ERASE_LABEL = tr("bar.erase");
	private static final Component CLEAR_LABEL = tr("bar.clear");
	private static final Component FOLLOW_ALL = tr("bar.follow_all");
	private static final Component STAY_ALL = tr("bar.stay_all");
	private static final Component FOLLOW = tr("group.follow");
	private static final Component STAY = tr("group.stay");
	private static final Component FOLLOW_SHORT = tr("group.follow.short");
	private static final Component STAY_SHORT = tr("group.stay.short");
	private static final Component GROUP_EMPTY = tr("group.empty");
	private static final Component GROUP_NEW = tr("group.new");
	private static final Component GROUPS_NONE_TITLE = tr("group.none.title");
	private static final Component GROUPS_NONE_BODY = tr("group.none.body");
	private static final Component GUARDS_SYNCING = tr("guards.syncing");
	private static final Component GUARDS_EMPTY_TITLE = tr("guards.empty.title");
	private static final Component GUARDS_EMPTY_BODY = tr("guards.empty.body");
	private static final Component TRAY_EMPTY = tr("tray.empty");
	private static final Component UNASSIGNED = tr("unassigned");
	private static final Component HINT_IDLE = tr("hint.idle");
	private static final Component HINT_MAP = tr("hint.map");
	private static final Component HINT_PAINTING = tr("hint.painting");
	private static final Component HINT_ERASING = tr("hint.erasing");
	private static final Component HINT_PANNING = tr("hint.panning");
	private static final Component HINT_SIDEBAR = tr("hint.groups");
	private static final Component HINT_DRAGGING = tr("hint.dragging");
	private static final Component HINT_SYNCING = tr("hint.syncing");
	private static final Component PAINT_ERASING = tr("paint.erasing");
	private static final Component MAP_EMPTY_TITLE = tr("map.empty.title");
	private static final Component MAP_EMPTY_BODY = tr("map.empty.body");
	private static final Component MAP_OFFSCREEN_TITLE = tr("map.offscreen.title");
	private static final Component MAP_OFFSCREEN_BODY = tr("map.offscreen.body");
	private static final Component UNAVAILABLE = tr("unavailable");
	private static final Component DRAG_UNASSIGN = tr("drag.unassign");
	private static final Component DRAG_NEW = tr("drag.new");
	private static final Component DRAG_CANCEL = tr("drag.cancel");
	private static final Component DIALOG_TITLE = tr("dialog.title");
	private static final Component DIALOG_KEYS = tr("dialog.keys");
	private static final Component DIALOG_SAVE = tr("dialog.save");
	private static final Component DIALOG_DISCARD = tr("dialog.discard");
	private static final Component DIALOG_CANCEL = tr("dialog.cancel");
	private static final Component HELP_DISMISS = tr("help.dismiss");
	private static final Component HELP_NEXT = tr("help.next");
	private static final Component HELP_TITLE_MAP = tr("help.title.zones");
	private static final Component HELP_TITLE_GROUPS = tr("help.title.groups");
	private static final Component RENAME_HINT = tr("rename.hint");
	private static final Component GUARD_DRAG_HINT = tr("guard.drag_hint").withStyle(ChatFormatting.DARK_GRAY);
	private static final Component GUARD_MENU_HINT = tr("guard.menu_hint").withStyle(ChatFormatting.DARK_GRAY);
	private static final Component RANK_COMMANDER = tr("rank.commander");
	private static final Component RANK_CAPTAIN = tr("rank.captain");
	private static final List<Component> TOOLTIP_RANK_COMMANDER = List.of(RANK_COMMANDER.copy().withStyle(ChatFormatting.GOLD));
	private static final List<Component> TOOLTIP_RANK_CAPTAIN = List.of(RANK_CAPTAIN.copy().withStyle(ChatFormatting.GRAY));
	private static final Component MENU_MAKE_COMMANDER = tr("menu.make_commander");
	private static final Component MENU_MAKE_CAPTAIN = tr("menu.make_captain");
	private static final Component CHUNK_HINT = tr("chunk.hint").withStyle(ChatFormatting.DARK_GRAY);
	private static final List<Component> TOOLTIP_HELP = List.of(tr("help.tooltip"));
	private static final List<Component> TOOLTIP_CLOSE = List.of(tr("close.tooltip"));
	private static final List<Component> TOOLTIP_ADD_GROUP = List.of(tr("add_group.tooltip"));
	private static final List<Component> TOOLTIP_SAVE = List.of(tr("save.tooltip"));
	private static final List<Component> TOOLTIP_REVERT = List.of(tr("revert.tooltip"));
	private static final List<Component> TOOLTIP_PALETTE_MORE = List.of(tr("bar.more.tooltip"));
	private static final List<Component> TOOLTIP_ERASE = List.of(tr("bar.erase.tooltip"));
	private static final List<Component> TOOLTIP_CLEAR = List.of(tr("bar.clear.tooltip"));
	private static final List<Component> TOOLTIP_FOLLOW_ALL = List.of(tr("bar.follow_all.tooltip"));
	private static final List<Component> TOOLTIP_STAY_ALL = List.of(tr("bar.stay_all.tooltip"));
	private static final List<Component> TOOLTIP_FOLLOW_GROUP = List.of(
		tr("bar.follow_group.tooltip"),
		tr("bar.follow_group.tooltip.body").withStyle(ChatFormatting.GRAY)
	);
	private static final List<Component> TOOLTIP_FOLLOW_GROUP_NONE = List.of(tr("bar.follow_group.none"));
	private static final Component[][] HELP_MAP = {
		helpRow("zones.select"), helpRow("zones.paint"), helpRow("zones.brush"), helpRow("zones.erase"),
		helpRow("zones.pan"), helpRow("zones.zoom"), helpRow("zones.color"), helpRow("zones.clear"),
		helpRow("zones.center"), helpRow("common.esc")
	};
	private static final Component[][] HELP_GROUPS = {
		helpRow("groups.drag"), helpRow("groups.unassign"), helpRow("groups.new"), helpRow("groups.collapse"),
		helpRow("groups.rename"), helpRow("groups.color"), helpRow("groups.clearcolor"), helpRow("groups.orders"),
		helpRow("groups.rank"), helpRow("groups.save")
	};
	private static final Component HELP_NOTE_MAP = tr("help.zones.note");
	private static final Component HELP_NOTE_GROUPS = tr("help.groups.note");

	// Shown once per game session so new players see the controls without hunting for them.
	private static boolean helpShownThisSession;
	private static String[] levelTextCache = new String[0];

	private final ClientTacticsDataStore dataStore = ClientTacticsDataStore.getInstance();
	private final ClientGuardRosterStore rosterStore = ClientGuardRosterStore.getInstance();
	private final ChunkMapWidget chunkMapWidget = new ChunkMapWidget(this.dataStore, GuardVillagersClient.terrainCache());
	private final GuardDragHandler dragHandler = new GuardDragHandler();
	private boolean helpVisible;
	private int helpPage;
	private boolean eraseTool;
	private boolean paletteOpen;
	private boolean followMenuOpen;
	private int followMenuOffset;
	private int orderGroupRow = -1;
	// Right-click rank menu on a guard card; a snapshot, since the roster entry is replaced on every sync.
	private ClientGuardRosterStore.GuardRosterEntry guardMenuGuard;
	private int guardMenuGroup;
	private int guardMenuX;
	private int guardMenuY;
	// Rank badge position of the card being drawn, picked up by addCardHit for the badge tooltip.
	private int drawnBadgeX = Integer.MIN_VALUE;
	private int drawnBadgeY;

	// Layout
	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;
	private int mapX;
	private int mapY;
	private int mapW;
	private int mapH;
	private int sideX;
	private int sideY;
	private int sideW;
	private int sideH;
	private int barX;
	private int barY;
	private int barW;
	private boolean barWide;

	// Buttons (reused every frame)
	private final UiButton helpButton = new UiButton();
	private final UiButton saveButton = new UiButton();
	private final UiButton revertButton = new UiButton();
	private final UiButton closeButton = new UiButton();
	private final UiButton addGroupButton = new UiButton();
	private final UiButton sortButton = new UiButton();
	private final UiButton paletteMoreButton = new UiButton();
	private final UiButton eraseButton = new UiButton();
	private final UiButton clearButton = new UiButton();
	private final UiButton followAllButton = new UiButton();
	private final UiButton stayAllButton = new UiButton();
	private final UiButton followGroupButton = new UiButton();
	private final UiButton[] buttons = {
		this.helpButton, this.saveButton, this.revertButton, this.closeButton, this.addGroupButton, this.sortButton,
		this.paletteMoreButton, this.eraseButton, this.clearButton, this.followAllButton, this.stayAllButton, this.followGroupButton
	};

	// Command bar swatches: colours bound to groups (in row order) plus the active colour
	private final RegionColor[] barSwatches = new RegionColor[MAX_BAR_SWATCHES];
	private int barSwatchCount;
	private int barSwatchX;
	private int barSwatchY;
	private RegionColor tooltipSwatch;
	private List<Component> swatchTooltip = List.of();

	// World context cache (resolving it builds strings, so do it once per level)
	private ClientLevel contextLevel;
	private ClientTacticsDataStore.WorldContext cachedContext;
	private String dimensionName = "";

	// Text caches
	private long hintKey = Long.MIN_VALUE;
	private Component hint = HINT_IDLE;
	private final FitText hintFit = new FitText();
	private final FitText statusFit = new FitText();
	private final FitText paintFit = new FitText();
	private final FitText subtitleFit = new FitText();
	private final FitText followGroupFit = new FitText();
	private final FitText newGroupFit = new FitText();
	private final FitText trayFit = new FitText();
	private long paintLabelKey = Long.MIN_VALUE;
	private String paintLabelName;
	private Component paintLabel = Component.empty();
	private long subtitleKey = Long.MIN_VALUE;
	private Component subtitle = Component.empty();
	private int zoomPercentShown = -1;
	private String zoomText = "";
	private long selectionChipKey = Long.MIN_VALUE;
	private String selectionChipText = "";
	private int trayLabelCount = -1;
	private String trayLabel = "";
	private int followGroupLabelRow = Integer.MIN_VALUE;
	private String followGroupLabelName;
	private Component followGroupLabel = Component.empty();
	private long hoveredChunkKey = Long.MIN_VALUE;
	private long hoveredChunkSince;
	private RegionColor tooltipZoneColor;
	private String tooltipGroupName;
	private int tooltipGroupCount = -1;
	private List<Component> chunkTooltip = List.of();

	// Roster grouped and sorted, rebuilt only when inputs change
	private int viewRosterRevision = -1;
	private int viewLocalRevision = -1;
	private int localRevision;
	private SortMode viewSort;
	private ClientTacticsDataStore.WorldContext viewContext;
	private int viewGroupCount;
	private final List<List<ClientGuardRosterStore.GuardRosterEntry>> groupMembers = new ArrayList<>();
	private final List<ClientGuardRosterStore.GuardRosterEntry> unassignedList = new ArrayList<>();
	private final List<String> groupNames = new ArrayList<>();
	private final Map<UUID, CardText> cardTexts = new HashMap<>();
	private int viewRosterSize;
	private boolean viewRosterLoaded;
	private SortMode sortMode = SortMode.GEAR;
	private final BitSet collapsedRows = new BitSet();
	// Groups whose loaded members are all following / all staying, so the header buttons can show the state.
	private final BitSet groupAllFollowing = new BitSet();
	private final BitSet groupAllStaying = new BitSet();
	private boolean autoCollapseChecked;

	// Sidebar list geometry and scroll
	private int listTop;
	private int listBottom;
	private int listScrollTarget;
	private double listScrollAnimated;
	private int listMaxScroll;

	// Hitboxes, pooled so the screen does not allocate per frame
	private final List<CardHit> cardHits = new ArrayList<>();
	private int cardHitCount;
	private final List<RowHit> rowHits = new ArrayList<>();
	private int rowHitCount;
	private final List<DropHit> dropHits = new ArrayList<>();
	private int dropHitCount;

	// Rename
	private EditBox groupRenameField;
	private int editingRow = -1;

	// Assignment state: pending = unsaved; awaiting = sent, waiting for the server's roster sync to confirm
	private final Map<UUID, Integer> pendingAssignments = new HashMap<>();
	private final Map<UUID, Integer> awaitingConfirmation = new HashMap<>();
	private long awaitingSince;
	private int awaitingSentCount;
	private int awaitingCheckedRevision = -1;
	// Per-batch results from GuardGroupResultPayload; the timeout only applies if they never arrive.
	private int awaitingBatches;
	private int awaitingRejected;
	private String awaitingReasonKey;
	private int awaitingReasonRow;
	private boolean showUnsavedDialog;
	private Runnable pendingAction;
	private int saveLabelKey = Integer.MIN_VALUE;

	// Drag feedback
	private DropHit dragTarget;
	private final DropHit dragTargetScratch = new DropHit();
	private Object dragLabelKey;
	private Component dragLabel;
	private int dragAccent = DROP_IDLE_BORDER;

	// Status line
	private Component statusText;
	private int statusColor;
	private long statusUntil;

	// Tooltip caches
	private ClientGuardRosterStore.GuardRosterEntry tooltipGuard;
	private int tooltipGuardGroup = Integer.MIN_VALUE;
	private List<Component> guardTooltip = List.of();
	private int tooltipRow = -1;
	private int tooltipRowPart = -1;
	private RegionColor tooltipRowColor;
	private List<Component> rowTooltip = List.of();
	private List<Component> groupLimitTooltipCache;

	public GuardTacticsScreen(GuardTacticsScreenHandler handler, Inventory inventory, Component title) {
		super(handler, inventory, title);
	}

	private static MutableComponent tr(String suffix) {
		return Component.translatable(KEY + suffix);
	}

	private static Component[] helpRow(String id) {
		return new Component[] {tr("help." + id + ".key"), tr("help." + id + ".desc")};
	}

	/** Trims {@code text} to {@code maxWidth} pixels, ending with "..." when it had to cut. */
	static String ellipsize(Font font, String text, int maxWidth) {
		if (text == null || maxWidth <= 0) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		int available = maxWidth - font.width("...");
		return available <= 0 ? "" : font.plainSubstrByWidth(text, available) + "...";
	}

	/**
	 * Shortens "Abigail Henderson" in readable steps instead of cutting mid-word: the full name, then "Abigail H.",
	 * then "Abigail", and only then an ellipsis.
	 */
	static String fitName(Font font, String name, int maxWidth) {
		if (font.width(name) <= maxWidth) {
			return name;
		}
		int space = name.indexOf(' ');
		if (space > 0 && space + 1 < name.length()) {
			String first = name.substring(0, space);
			String initial = first + " " + name.charAt(space + 1) + ".";
			if (font.width(initial) <= maxWidth) {
				return initial;
			}
			if (font.width(first) <= maxWidth) {
				return first;
			}
		}
		return ellipsize(font, name, maxWidth);
	}

	static String levelText(int level) {
		int index = Mth.clamp(level, 0, 999);
		if (index >= levelTextCache.length) {
			String[] grown = new String[Math.max(index + 1, 32)];
			System.arraycopy(levelTextCache, 0, grown, 0, levelTextCache.length);
			levelTextCache = grown;
		}
		String cached = levelTextCache[index];
		if (cached == null) {
			cached = Component.translatable(KEY + "level", index).getString();
			levelTextCache[index] = cached;
		}
		return cached;
	}

	@Override
	protected void init() {
		super.init();
		this.leftPos = 0;
		this.topPos = 0;
		// Language may have changed since the last open.
		levelTextCache = new String[0];
		this.cardTexts.clear();
		this.viewRosterRevision = -1;
		this.hintKey = Long.MIN_VALUE;
		this.paintLabelKey = Long.MIN_VALUE;
		this.subtitleKey = Long.MIN_VALUE;
		this.saveLabelKey = Integer.MIN_VALUE;
		this.followGroupLabelRow = Integer.MIN_VALUE;
		this.trayLabelCount = -1;
		this.zoomPercentShown = -1;
		this.selectionChipKey = Long.MIN_VALUE;
		this.tooltipGuard = null;
		this.tooltipSwatch = null;
		this.tooltipRow = -1;
		this.hoveredChunkKey = Long.MIN_VALUE;

		this.helpButton.setLabel(this.font, HELP_LABEL, 8, TOOLTIP_HELP);
		this.closeButton.setLabel(this.font, CLOSE_LABEL, 8, TOOLTIP_CLOSE);
		this.revertButton.setLabel(this.font, REVERT, 8, TOOLTIP_REVERT);
		this.addGroupButton.setLabel(this.font, ADD_GROUP, 8, TOOLTIP_ADD_GROUP);
		this.paletteMoreButton.setLabel(this.font, PALETTE_MORE, 6, TOOLTIP_PALETTE_MORE);
		this.eraseButton.setLabel(this.font, ERASE_LABEL, 8, TOOLTIP_ERASE);
		this.clearButton.setLabel(this.font, CLEAR_LABEL, 8, TOOLTIP_CLEAR);
		this.followAllButton.setLabel(this.font, FOLLOW_ALL, 8, TOOLTIP_FOLLOW_ALL);
		this.stayAllButton.setLabel(this.font, STAY_ALL, 8, TOOLTIP_STAY_ALL);
		this.sortButton.setLabel(this.font, this.sortMode.label, 6, this.sortMode.tooltip);

		this.groupRenameField = new EditBox(this.font, 0, 0, 80, 12, RENAME_HINT);
		this.groupRenameField.setVisible(false);
		this.groupRenameField.setMaxLength(24);
		this.groupRenameField.setHint(RENAME_HINT);
		this.addRenderableWidget(this.groupRenameField);
		this.editingRow = -1;
		this.listScrollAnimated = this.listScrollTarget;
		this.rosterStore.clearGroupResults();

		if (this.minecraft != null && this.minecraft.player != null) {
			this.chunkMapWidget.ensureCameraCentered(this.minecraft.player.chunkPosition());
		}
		if (!helpShownThisSession) {
			helpShownThisSession = true;
			this.helpVisible = true;
			this.helpPage = 0;
		}
	}

	// Replaces the container render path on purpose: this menu has no slots, labels or carried item.
	// The screen background is extracted separately by Screen#extractRenderStateWithTooltipAndSubtitles.
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.computeLayout();
		this.processGroupResults();
		this.checkAwaitingConfirmation();
		graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, PANEL_BACKGROUND);
		graphics.outline(this.panelX, this.panelY, this.panelWidth, this.panelHeight, PANEL_BORDER);

		boolean overlayOpen = this.showUnsavedDialog || this.helpVisible;
		int contentMouseX = overlayOpen ? -1 : mouseX;
		int contentMouseY = overlayOpen ? -1 : mouseY;
		boolean popupOpen = this.paletteOpen || this.followMenuOpen || this.guardMenuGuard != null;
		int underPopupX = popupOpen ? -1 : contentMouseX;
		int underPopupY = popupOpen ? -1 : contentMouseY;

		ClientTacticsDataStore.WorldContext worldContext = this.worldContext();
		// Drop target is resolved against last frame's hitboxes (same geometry) before they are rebuilt.
		this.updateDragTarget(contentMouseX, contentMouseY);
		this.resetHits();
		if (worldContext != null) {
			this.refreshRosterView(worldContext);
		}

		this.renderMap(graphics, worldContext, underPopupX, underPopupY);
		this.renderSidebar(graphics, worldContext, underPopupX, underPopupY);
		this.renderCommandBar(graphics, worldContext, underPopupX, underPopupY);
		this.renderHeader(graphics, underPopupX, underPopupY);
		if (this.paletteOpen) {
			this.renderPalettePopup(graphics, contentMouseX, contentMouseY);
		}
		if (this.followMenuOpen) {
			this.renderFollowMenu(graphics, contentMouseX, contentMouseY);
		}
		if (this.guardMenuGuard != null) {
			this.renderGuardMenu(graphics, contentMouseX, contentMouseY);
		}

		if (this.editingRow >= 0 && this.groupRenameField != null && !this.groupRenameField.isVisible()) {
			this.cancelGroupRename();
		}
		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			this.groupRenameField.extractRenderState(graphics, mouseX, mouseY, delta);
		}
		if (this.dragHandler.isActive()) {
			this.dragHandler.render(graphics, this.font, this.dragLabel, this.dragAccent);
		}
		if (this.helpVisible) {
			this.renderHelp(graphics);
		}
		if (this.showUnsavedDialog) {
			this.renderUnsavedDialog(graphics, mouseX, mouseY);
		}
		if (!overlayOpen && !this.dragHandler.isActive()) {
			this.renderTooltips(graphics, worldContext, contentMouseX, contentMouseY, popupOpen);
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		double mouseX = click.x();
		double mouseY = click.y();
		int button = click.button();

		if (this.showUnsavedDialog) {
			return this.handleDialogClick(mouseX, mouseY);
		}
		if (this.helpVisible) {
			// Any click dismisses the legend; it never leaks through to paint or drag underneath.
			this.helpVisible = false;
			return true;
		}
		if (this.paletteOpen) {
			this.handlePaletteClick(mouseX, mouseY);
			return true;
		}
		if (this.followMenuOpen) {
			this.handleFollowMenuClick(mouseX, mouseY);
			return true;
		}
		if (this.guardMenuGuard != null) {
			this.handleGuardMenuClick(mouseX, mouseY);
			return true;
		}

		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			if (this.isInsideRenameField(mouseX, mouseY)) {
				return this.groupRenameField.mouseClicked(click, doubleClick);
			}
			this.commitGroupRename();
		}

		if (button == 0 && this.handleButtonClick(mouseX, mouseY)) {
			return true;
		}
		if (button == 0) {
			RegionColor swatch = this.barSwatchAt(mouseX, mouseY);
			if (swatch != null) {
				this.selectColor(swatch);
				return true;
			}
		}
		if (this.contains(this.sideX, this.sideY, this.sideW, this.sideH, mouseX, mouseY)) {
			this.handleSidebarClick(mouseX, mouseY, button, click.hasShiftDown(), doubleClick);
			return true;
		}
		if (this.chunkMapWidget.mouseClicked(
			mouseX,
			mouseY,
			button,
			// The Erase tool flips what Shift does, so Shift+right-click paints while erasing.
			click.hasShiftDown() != this.eraseTool,
			this.minecraft == null ? null : this.minecraft.level,
			this.worldContext()
		)) {
			return true;
		}
		if (this.contains(this.panelX, this.panelY, this.panelWidth, this.panelHeight, mouseX, mouseY)) {
			return true;
		}
		return super.mouseClicked(click, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
		if (this.showUnsavedDialog || this.helpVisible || this.paletteOpen || this.followMenuOpen || this.guardMenuGuard != null) {
			return true;
		}
		if (this.dragHandler.isArmed()) {
			this.dragHandler.updateDrag(click.x(), click.y());
			return true;
		}
		if (this.chunkMapWidget.mouseDragged(
			click.x(),
			click.y(),
			click.button(),
			deltaX,
			deltaY,
			this.minecraft == null ? null : this.minecraft.level,
			this.worldContext()
		)) {
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		if (this.dragHandler.isArmed()) {
			if (this.dragHandler.isActive()) {
				this.handleDrop(click.x(), click.y());
			} else {
				this.dragHandler.cancel();
			}
			return true;
		}
		if (this.chunkMapWidget.mouseReleased(click.x(), click.y(), click.button())) {
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (this.followMenuOpen && !this.showUnsavedDialog && !this.helpVisible) {
			this.followMenuOffset = Mth.clamp(this.followMenuOffset - (int) Math.signum(verticalAmount), 0,
				Math.max(0, this.viewGroupCount - this.followMenuVisibleCount()));
			return true;
		}
		if (this.showUnsavedDialog || this.helpVisible || this.paletteOpen || this.guardMenuGuard != null) {
			return true;
		}
		if (this.contains(this.sideX, this.sideY, this.sideW, this.sideH, mouseX, mouseY)) {
			int step = (int) Math.round(-verticalAmount * WHEEL_SCROLL_PIXELS);
			this.listScrollTarget = Mth.clamp(this.listScrollTarget + step, 0, this.listMaxScroll);
			return true;
		}
		if (this.chunkMapWidget.mouseScrolled(mouseX, mouseY, verticalAmount)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent keyInput) {
		int key = keyInput.input();
		if (this.showUnsavedDialog) {
			if (keyInput.isEscape()) {
				this.closeDialog();
			} else if (keyInput.isConfirmation()) {
				this.dialogSave();
			}
			// The dialog is modal; swallow everything else (including the inventory key).
			return true;
		}

		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			if (keyInput.isConfirmation()) {
				this.commitGroupRename();
				return true;
			}
			if (keyInput.isEscape()) {
				this.cancelGroupRename();
				return true;
			}
			// Swallow keys the field consumes as text, or typing the inventory key ("E") closes the screen.
			if (this.groupRenameField.keyPressed(keyInput) || this.groupRenameField.canConsumeInput()) {
				return true;
			}
		}

		// Esc peels off one transient layer at a time before it closes the screen (vanilla behaviour).
		if (keyInput.isEscape()) {
			if (this.dragHandler.isArmed()) {
				this.dragHandler.cancel();
				return true;
			}
			if (this.paletteOpen || this.followMenuOpen || this.guardMenuGuard != null) {
				this.paletteOpen = false;
				this.followMenuOpen = false;
				this.guardMenuGuard = null;
				return true;
			}
			if (this.helpVisible) {
				this.helpVisible = false;
				return true;
			}
			return super.keyPressed(keyInput);
		}

		if (key == GLFW.GLFW_KEY_H) {
			this.toggleHelp();
			return true;
		}
		if (this.helpVisible) {
			this.helpVisible = false;
		}
		if (key == GLFW.GLFW_KEY_S && keyInput.hasControlDown()) {
			this.saveAssignments();
			return true;
		}
		if (this.handleMapKey(keyInput)) {
			return true;
		}
		return super.keyPressed(keyInput);
	}

	private boolean handleMapKey(KeyEvent keyInput) {
		int key = keyInput.input();
		int digit = keyInput.getDigit();
		if (digit >= 0 && !keyInput.hasControlDown()) {
			RegionColor color = RegionColor.forHotkeyDigit(digit);
			if (color != null) {
				this.selectColor(color);
				return true;
			}
		}
		ClientTacticsDataStore.WorldContext context = this.worldContext();
		switch (key) {
			case GLFW.GLFW_KEY_C -> this.chunkMapWidget.clearSelection();
			case GLFW.GLFW_KEY_X -> this.eraseTool = !this.eraseTool;
			case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> this.chunkMapWidget.paintSelection(context, true);
			case GLFW.GLFW_KEY_SPACE -> {
				if (this.minecraft != null && this.minecraft.player != null) {
					this.chunkMapWidget.centerOn(this.minecraft.player.chunkPosition());
				}
			}
			case GLFW.GLFW_KEY_LEFT -> this.chunkMapWidget.panByPixels(-KEY_PAN_PIXELS, 0);
			case GLFW.GLFW_KEY_RIGHT -> this.chunkMapWidget.panByPixels(KEY_PAN_PIXELS, 0);
			case GLFW.GLFW_KEY_UP -> this.chunkMapWidget.panByPixels(0, -KEY_PAN_PIXELS);
			case GLFW.GLFW_KEY_DOWN -> this.chunkMapWidget.panByPixels(0, KEY_PAN_PIXELS);
			case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> this.chunkMapWidget.zoomSteps(1);
			case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> this.chunkMapWidget.zoomSteps(-1);
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent charInput) {
		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			return this.groupRenameField.charTyped(charInput);
		}
		return super.charTyped(charInput);
	}

	private boolean handleButtonClick(double mouseX, double mouseY) {
		if (this.helpButton.isClickable(mouseX, mouseY)) {
			this.toggleHelp();
		} else if (this.saveButton.isClickable(mouseX, mouseY)) {
			this.saveAssignments();
		} else if (this.revertButton.isClickable(mouseX, mouseY)) {
			this.revertAssignments();
		} else if (this.closeButton.isClickable(mouseX, mouseY)) {
			this.onClose();
		} else if (this.addGroupButton.isClickable(mouseX, mouseY)) {
			this.addGroup(this.worldContext());
		} else if (this.sortButton.isClickable(mouseX, mouseY)) {
			this.sortMode = this.sortMode.next();
			this.sortButton.setLabel(this.font, this.sortMode.label, 6, this.sortMode.tooltip);
		} else if (this.paletteMoreButton.isClickable(mouseX, mouseY)) {
			this.paletteOpen = true;
		} else if (this.eraseButton.isClickable(mouseX, mouseY)) {
			this.eraseTool = !this.eraseTool;
		} else if (this.clearButton.isClickable(mouseX, mouseY)) {
			this.chunkMapWidget.clearSelection();
		} else if (this.followAllButton.isClickable(mouseX, mouseY)) {
			this.sendOrder("guards follow", tr("status.follow_all"));
		} else if (this.stayAllButton.isClickable(mouseX, mouseY)) {
			this.sendOrder("guards stay", tr("status.stay_all"));
		} else if (this.followGroupButton.isClickable(mouseX, mouseY)) {
			// The arrow end opens the chooser; the label end repeats the order for the chosen group.
			boolean onArrow = mouseX >= this.followGroupButton.x + this.followGroupButton.w - 12;
			if (onArrow || this.orderGroupRow < 0 || this.orderGroupRow >= this.viewGroupCount) {
				this.followMenuOpen = this.viewGroupCount > 0;
			} else {
				this.orderGroup(this.orderGroupRow, true);
			}
		} else {
			return false;
		}
		return true;
	}

	private void toggleHelp() {
		if (!this.helpVisible) {
			this.helpVisible = true;
			this.helpPage = 0;
		} else if (!this.helpFitsTwoColumns() && this.helpPage == 0) {
			this.helpPage = 1;
		} else {
			this.helpVisible = false;
		}
	}

	private void selectColor(RegionColor color) {
		this.chunkMapWidget.setActiveColor(color);
		this.eraseTool = false;
	}

	// ---------------------------------------------------------------- header

	private void renderHeader(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int y = this.panelY + 4;
		int titleX = this.panelX + 6;
		graphics.text(this.font, TITLE, titleX, y, TEXT_PRIMARY, false);

		int pendingCount = this.pendingAssignments.size();
		this.drawButton(graphics, this.closeButton, mouseX, mouseY, STYLE_DARK);
		this.drawButton(graphics, this.saveButton, mouseX, mouseY, pendingCount > 0 ? STYLE_WARN : STYLE_DARK);
		if (pendingCount > 0) {
			// Unsaved dot, drawn as a small square so it does not depend on font glyphs.
			graphics.fill(this.saveButton.x + 4, this.saveButton.y + 4, this.saveButton.x + 8, this.saveButton.y + 8, KEY_COLOR);
		}
		this.drawButton(graphics, this.revertButton, mouseX, mouseY, STYLE_DARK);
		this.drawButton(graphics, this.helpButton, mouseX, mouseY, this.helpVisible ? STYLE_ON : STYLE_DARK);

		int subtitleX = titleX + this.font.width(TITLE) + 8;
		int subtitleMax = this.helpButton.x - 6 - subtitleX;
		graphics.text(this.font, this.subtitleFit.fit(this.font, this.subtitle(), subtitleMax), subtitleX, y, TEXT_MUTED, false);
	}

	private Component subtitle() {
		long key = ((long) this.viewRosterSize << 40) | ((long) this.viewGroupCount << 20) | (this.viewRosterLoaded ? 1 : 0);
		String dimension = this.dimensionName;
		if (key != this.subtitleKey || this.subtitle == null || !this.subtitle.getString().endsWith(dimension)) {
			this.subtitleKey = key;
			this.subtitle = this.viewRosterLoaded
				? Component.translatable(KEY + "subtitle", this.viewRosterSize, this.viewGroupCount, dimension)
				: Component.translatable(KEY + "subtitle.syncing", dimension);
		}
		return this.subtitle;
	}

	private void layoutHeaderButtons() {
		int y = this.panelY + 2;
		int right = this.panelX + this.panelWidth - 4;
		this.closeButton.place(right - this.closeButton.w, y, BUTTON_HEIGHT);
		int pendingCount = this.pendingAssignments.size();
		boolean awaiting = !this.awaitingConfirmation.isEmpty();
		int labelKey = pendingCount > 0 ? pendingCount : awaiting ? -2 : -1;
		if (labelKey != this.saveLabelKey) {
			this.saveLabelKey = labelKey;
			Component label = pendingCount > 0 ? Component.translatable(KEY + "save", pendingCount) : awaiting ? SAVING : SAVED;
			this.saveButton.setLabel(this.font, label, pendingCount > 0 ? 16 : 8, TOOLTIP_SAVE);
		}
		this.saveButton.place(this.closeButton.x - 3 - this.saveButton.w, y, BUTTON_HEIGHT);
		this.saveButton.enabled = pendingCount > 0;
		this.revertButton.visible = pendingCount > 0;
		int helpRight = this.saveButton.x - 3;
		if (this.revertButton.visible) {
			this.revertButton.place(helpRight - this.revertButton.w, y, BUTTON_HEIGHT);
			helpRight = this.revertButton.x - 3;
		}
		this.helpButton.place(helpRight - this.helpButton.w, y, BUTTON_HEIGHT);
	}

	// ---------------------------------------------------------------- map

	private void renderMap(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		if (this.minecraft != null && this.minecraft.player != null) {
			this.chunkMapWidget.ensureCameraCentered(this.minecraft.player.chunkPosition());
		}
		boolean shiftDown = this.minecraft != null && this.minecraft.hasShiftDown();
		this.chunkMapWidget.setBounds(this.mapX, this.mapY, this.mapW, this.mapH);
		this.chunkMapWidget.render(graphics, this.font, this.minecraft == null ? null : this.minecraft.level, worldContext, mouseX, mouseY, shiftDown != this.eraseTool);
		graphics.outline(this.mapX - 1, this.mapY - 1, this.mapW + 2, this.mapH + 2, PANEL_BORDER);
		if (this.minecraft != null && this.minecraft.player != null) {
			this.chunkMapWidget.drawPlayerMarker(graphics, this.minecraft.player.getX() / 16.0D, this.minecraft.player.getZ() / 16.0D, this.minecraft.player.getYRot());
		}

		if (worldContext != null && this.chunkMapWidget.visibleChunkCount() == 0) {
			boolean anyDiscovered = this.chunkMapWidget.hasDiscoveredChunks();
			this.renderCenteredMessage(
				graphics,
				anyDiscovered ? MAP_OFFSCREEN_TITLE : MAP_EMPTY_TITLE,
				anyDiscovered ? MAP_OFFSCREEN_BODY : MAP_EMPTY_BODY,
				this.mapX,
				this.mapY,
				this.mapW,
				this.mapH
			);
		}

		// Overlay chips, top-left: zoom and selection size.
		int zoomPercent = (int) Math.round(this.chunkMapWidget.zoom() * 100.0D);
		if (zoomPercent != this.zoomPercentShown) {
			this.zoomPercentShown = zoomPercent;
			this.zoomText = Component.translatable(KEY + "zoom", zoomPercent).getString();
		}
		int chipX = this.mapX + 3;
		int chipY = this.mapY + 3;
		chipX = this.drawChip(graphics, this.zoomText, chipX, chipY, ACCENT_ZONES) + 3;
		if (this.chunkMapWidget.hasSelection()) {
			long key = ((long) this.chunkMapWidget.selectionWidth() << 32) | this.chunkMapWidget.selectionHeight();
			if (key != this.selectionChipKey) {
				this.selectionChipKey = key;
				this.selectionChipText = Component.translatable(KEY + "selection.chip", this.chunkMapWidget.selectionWidth(), this.chunkMapWidget.selectionHeight()).getString();
			}
			if (chipX + this.font.width(this.selectionChipText) + 6 < this.mapX + this.mapW) {
				this.drawChip(graphics, this.selectionChipText, chipX, chipY, TEXT_SECONDARY);
			}
		}
	}

	private int drawChip(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
		int w = this.font.width(text) + 6;
		graphics.fill(x, y, x + w, y + 11, CHIP_BACKGROUND);
		graphics.outline(x, y, w, 11, PANEL_BORDER);
		graphics.text(this.font, text, x + 3, y + 2, color, false);
		return x + w;
	}

	private void renderChunkTooltip(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		ChunkPos hovered = this.chunkMapWidget.hoveredChunk();
		ChunkMapWidget map = this.chunkMapWidget;
		if (hovered == null || worldContext == null || map.isPainting() || map.isSelecting() || map.isPanning()) {
			this.hoveredChunkKey = Long.MIN_VALUE;
			return;
		}
		long key = ChunkPos.pack(hovered.x(), hovered.z());
		long now = System.currentTimeMillis();
		if (key != this.hoveredChunkKey) {
			this.hoveredChunkKey = key;
			this.hoveredChunkSince = now;
			this.chunkTooltip = List.of();
		}
		// Short delay so the tooltip does not chase the cursor while sweeping across the map.
		if (now - this.hoveredChunkSince < CHUNK_TOOLTIP_DELAY_MILLIS) {
			return;
		}
		RegionColor zoneColor = this.dataStore.getRegionColor(worldContext, hovered.x(), hovered.z());
		int boundRow = this.rowForColor(worldContext, zoneColor);
		String groupName = boundRow >= 0 ? this.groupNames.get(boundRow) : null;
		int memberCount = boundRow >= 0 ? this.groupMembers.get(boundRow).size() : -1;
		if (this.chunkTooltip.isEmpty() || zoneColor != this.tooltipZoneColor || !Objects.equals(groupName, this.tooltipGroupName) || memberCount != this.tooltipGroupCount) {
			this.tooltipZoneColor = zoneColor;
			this.tooltipGroupName = groupName;
			this.tooltipGroupCount = memberCount;
			List<Component> lines = new ArrayList<>(4);
			lines.add(Component.translatable(KEY + "chunk.position", hovered.x(), hovered.z()));
			lines.add(zoneColor == RegionColor.NONE
				? tr("chunk.zone_none").withStyle(ChatFormatting.GRAY)
				: Component.translatable(KEY + "chunk.zone", zoneColor.labelText(), zoneColor.hotkeyLabel()).withStyle(ChatFormatting.GRAY));
			if (zoneColor != RegionColor.NONE) {
				lines.add(groupName == null
					? tr("chunk.group_none").withStyle(ChatFormatting.DARK_GRAY)
					: Component.translatable(KEY + "chunk.group", groupName, memberCount).withStyle(ChatFormatting.GRAY));
			}
			lines.add(CHUNK_HINT);
			this.chunkTooltip = lines;
		}
		graphics.setComponentTooltipForNextFrame(this.font, this.chunkTooltip, mouseX, mouseY);
	}

	/** Lowest group row bound to {@code color}, or -1. Iterates rows directly to avoid an Optional per frame. */
	private int rowForColor(ClientTacticsDataStore.WorldContext worldContext, RegionColor color) {
		if (worldContext == null || color == null || color == RegionColor.NONE) {
			return -1;
		}
		for (int row = 0; row < this.viewGroupCount; row++) {
			if (this.dataStore.getGroupColor(worldContext, row) == color) {
				return row;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------- command bar

	private void renderCommandBar(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		int barRight = this.barX + this.barW;
		graphics.fill(this.barX, this.barY, barRight, this.barY + BAR_HEIGHT, BAR_BACKGROUND);
		graphics.outline(this.barX, this.barY, this.barW, BAR_HEIGHT, PANEL_BORDER);

		// Swatches: colours already bound to groups, in group order, then the active colour if unbound.
		this.barSwatchCount = 0;
		if (worldContext != null) {
			for (int row = 0; row < this.viewGroupCount && this.barSwatchCount < MAX_BAR_SWATCHES; row++) {
				this.addBarSwatch(this.dataStore.getGroupColor(worldContext, row));
			}
		}
		this.addBarSwatch(this.chunkMapWidget.activeColor());

		int rowY = this.barY + 3;
		int textY = this.barY + 21;
		boolean wide = this.barWide;
		int x = this.barX + 4;
		if (wide) {
			graphics.text(this.font, ZONE_LABEL, x, rowY + 2, ACCENT_ZONES, false);
			x += this.font.width(ZONE_LABEL) + 5;
		}
		int ordersWidth = this.ordersWidth(wide);
		int zoneLimit = wide ? barRight - 4 - ordersWidth - 10 : barRight - 4;
		int toolsWidth = this.paletteMoreButton.w + this.eraseButton.w + this.clearButton.w + 9;
		int maxSwatches = Math.max(1, (zoneLimit - x - toolsWidth) / SWATCH_STRIDE);
		this.barSwatchCount = Math.min(this.barSwatchCount, maxSwatches);
		this.barSwatchX = x;
		this.barSwatchY = rowY + 1;
		RegionColor active = this.chunkMapWidget.activeColor();
		for (int i = 0; i < this.barSwatchCount; i++) {
			RegionColor color = this.barSwatches[i];
			int sx = x + i * SWATCH_STRIDE;
			int sy = this.barSwatchY;
			boolean selected = color == active && !this.eraseTool;
			boolean hovered = this.contains(sx, sy, SWATCH_SIZE, SWATCH_SIZE, mouseX, mouseY);
			if (selected) {
				graphics.outline(sx - 2, sy - 2, SWATCH_SIZE + 4, SWATCH_SIZE + 4, 0xFFFFFFFF);
			}
			graphics.fill(sx, sy, sx + SWATCH_SIZE, sy + SWATCH_SIZE, color.swatchArgb());
			graphics.outline(sx, sy, SWATCH_SIZE, SWATCH_SIZE, hovered ? TEXT_PRIMARY : 0xFF000000);
			graphics.text(this.font, color.hotkeyLabel(), sx + 3, sy + 1, color.prefersDarkText() ? 0xFF10161D : 0xFFFFFFFF, false);
		}
		x += this.barSwatchCount * SWATCH_STRIDE;
		this.paletteMoreButton.place(x, rowY, BUTTON_HEIGHT);
		this.drawButton(graphics, this.paletteMoreButton, mouseX, mouseY, this.paletteOpen ? STYLE_ON : STYLE_DARK);
		this.eraseButton.place(this.paletteMoreButton.x + this.paletteMoreButton.w + 3, rowY, BUTTON_HEIGHT);
		this.drawButton(graphics, this.eraseButton, mouseX, mouseY, this.eraseTool ? STYLE_ON : STYLE_DARK);
		this.clearButton.place(this.eraseButton.x + this.eraseButton.w + 3, rowY, BUTTON_HEIGHT);
		this.clearButton.enabled = this.chunkMapWidget.hasSelection();
		this.drawButton(graphics, this.clearButton, mouseX, mouseY, STYLE_DARK);
		int zoneToolsEnd = this.clearButton.x + this.clearButton.w;

		// Orders: right-aligned on the first row when wide, on the second row otherwise.
		int ordersY = wide ? rowY : this.barY + 18;
		int ox = wide ? barRight - 4 - ordersWidth : this.barX + 4;
		if (wide) {
			graphics.text(this.font, ORDERS_LABEL, ox, rowY + 2, ACCENT_GROUPS, false);
			ox += this.font.width(ORDERS_LABEL) + 5;
		}
		this.followAllButton.place(ox, ordersY, BUTTON_HEIGHT);
		this.drawButton(graphics, this.followAllButton, mouseX, mouseY, STYLE_GREY);
		this.stayAllButton.place(this.followAllButton.x + this.followAllButton.w + 3, ordersY, BUTTON_HEIGHT);
		this.drawButton(graphics, this.stayAllButton, mouseX, mouseY, STYLE_GREY);
		this.layoutFollowGroupButton(this.stayAllButton.x + this.stayAllButton.w + 3, ordersY);
		this.drawFollowGroupButton(graphics, mouseX, mouseY);
		int ordersEnd = this.followGroupButton.x + this.followGroupButton.w;

		// Text line(s): what the brush does, then the context hint or the latest status.
		Component paint = this.paintLabel(worldContext);
		Component hintOrStatus = this.hasStatus() ? this.statusText : this.currentHint(mouseX, mouseY);
		int hintColor = this.hasStatus() ? this.statusColor : TEXT_SECONDARY;
		if (wide) {
			int ordersX = barRight - 4 - ordersWidth;
			graphics.text(this.font, this.paintFit.fit(this.font, paint, ordersX - 10 - (this.barX + 4)), this.barX + 4, textY, TEXT_SECONDARY, false);
			graphics.text(this.font, this.hintFit.fit(this.font, hintOrStatus, barRight - 4 - ordersX), ordersX, textY, hintColor, false);
		} else {
			// Narrow: brush label shares row 1 with the zone tools, the hint shares row 2 with the orders.
			int paintX = zoneToolsEnd + 6;
			graphics.text(this.font, this.paintFit.fit(this.font, paint, barRight - 4 - paintX), paintX, rowY + 2, TEXT_SECONDARY, false);
			int hintX = ordersEnd + 6;
			graphics.text(this.font, this.hintFit.fit(this.font, hintOrStatus, barRight - 4 - hintX), hintX, ordersY + 2, hintColor, false);
		}
	}

	private void addBarSwatch(RegionColor color) {
		if (color == null || color == RegionColor.NONE || this.barSwatchCount >= MAX_BAR_SWATCHES) {
			return;
		}
		for (int i = 0; i < this.barSwatchCount; i++) {
			if (this.barSwatches[i] == color) {
				return;
			}
		}
		this.barSwatches[this.barSwatchCount++] = color;
	}

	private RegionColor barSwatchAt(double mouseX, double mouseY) {
		if (mouseY < this.barSwatchY - 1 || mouseY >= this.barSwatchY + SWATCH_SIZE + 1) {
			return null;
		}
		for (int i = 0; i < this.barSwatchCount; i++) {
			int sx = this.barSwatchX + i * SWATCH_STRIDE;
			if (mouseX >= sx - 1 && mouseX < sx + SWATCH_SIZE + 1) {
				return this.barSwatches[i];
			}
		}
		return null;
	}

	private int ordersWidth(boolean withLabel) {
		int width = this.followAllButton.w + this.stayAllButton.w + this.followGroupButton.w + 6;
		return withLabel ? width + this.font.width(ORDERS_LABEL) + 5 : width;
	}

	private void layoutFollowGroupButton(int x, int y) {
		this.followGroupButton.place(x, y, BUTTON_HEIGHT);
	}

	private void updateFollowGroupLabel() {
		int row = this.orderGroupRow < this.viewGroupCount ? this.orderGroupRow : -1;
		String name = row >= 0 ? this.groupNames.get(row) : null;
		if (row != this.followGroupLabelRow || !Objects.equals(name, this.followGroupLabelName)) {
			this.followGroupLabelRow = row;
			this.followGroupLabelName = name;
			this.followGroupLabel = name == null ? tr("bar.follow_group.empty") : Component.translatable(KEY + "bar.follow_group", name);
		}
		// Long group names are trimmed so the button never pushes the bar off screen.
		int textWidth = Math.min(this.font.width(this.followGroupLabel), 92);
		this.followGroupButton.label = this.followGroupLabel;
		this.followGroupButton.labelWidth = textWidth;
		this.followGroupButton.w = textWidth + 18;
		this.followGroupButton.tooltip = row >= 0 ? TOOLTIP_FOLLOW_GROUP : TOOLTIP_FOLLOW_GROUP_NONE;
		this.followGroupButton.enabled = this.viewGroupCount > 0;
	}

	private void drawFollowGroupButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		UiButton button = this.followGroupButton;
		boolean hovered = button.enabled && button.contains(mouseX, mouseY);
		int[] style = STYLE_GREY;
		graphics.fill(button.x, button.y, button.x + button.w, button.y + button.h, button.enabled ? (hovered ? style[1] : style[0]) : DISABLED_FILL);
		graphics.outline(button.x, button.y, button.w, button.h, button.enabled ? (hovered ? TEXT_PRIMARY : style[2]) : DISABLED_BORDER);
		String text = this.followGroupFit.fit(this.font, button.label, button.labelWidth);
		graphics.text(this.font, text, button.x + 4, button.y + 2, button.enabled ? TEXT_PRIMARY : TEXT_MUTED, false);
		// Divider and a drawn down-arrow for the chooser end.
		int arrowX = button.x + button.w - 12;
		graphics.fill(arrowX, button.y + 2, arrowX + 1, button.y + button.h - 2, button.enabled ? style[2] : DISABLED_BORDER);
		this.drawArrow(graphics, arrowX + 4, button.y + 5, true, button.enabled ? TEXT_PRIMARY : TEXT_MUTED);
	}

	private Component paintLabel(ClientTacticsDataStore.WorldContext worldContext) {
		if (this.eraseTool) {
			return PAINT_ERASING;
		}
		RegionColor color = this.chunkMapWidget.activeColor();
		int row = this.rowForColor(worldContext, color);
		String name = row >= 0 ? this.groupNames.get(row) : null;
		long key = ((long) color.id() << 32) | (row & 0xFFFFFFFFL);
		if (key != this.paintLabelKey || !Objects.equals(name, this.paintLabelName)) {
			this.paintLabelKey = key;
			this.paintLabelName = name;
			Component colorName = color.labelText().copy().withColor(color.swatchArgb() | 0xFF000000);
			this.paintLabel = name == null
				? Component.translatable(KEY + "paint.unbound", colorName)
				: Component.translatable(KEY + "paint.bound", colorName, name);
		}
		return this.paintLabel;
	}

	// The hint depends on what the cursor is doing; components are rebuilt only when that state changes.
	private Component currentHint(int mouseX, int mouseY) {
		ChunkMapWidget map = this.chunkMapWidget;
		int state;
		if (this.dragHandler.isActive()) {
			state = 8;
		} else if (map.isPanning()) {
			state = 1;
		} else if (map.isPainting()) {
			state = map.isErasing() ? 2 : 3;
		} else if (map.isSelecting()) {
			state = 4;
		} else if (map.isMouseOver(mouseX, mouseY)) {
			state = map.hasSelection() ? 6 : 7;
		} else if (this.contains(this.sideX, this.sideY, this.sideW, this.sideH, mouseX, mouseY)) {
			state = this.viewRosterLoaded ? 9 : 10;
		} else {
			state = 0;
		}
		long key = ((long) state << 48) | ((long) map.selectionWidth() << 32) | ((long) map.selectionHeight() << 16) | map.activeColor().id();
		if (key != this.hintKey) {
			this.hintKey = key;
			this.hint = switch (state) {
				case 1 -> HINT_PANNING;
				case 2 -> HINT_ERASING;
				case 3 -> HINT_PAINTING;
				case 4 -> Component.translatable(KEY + "hint.selecting", map.selectionWidth(), map.selectionHeight());
				case 6 -> Component.translatable(KEY + "hint.selection", map.selectionWidth(), map.selectionHeight());
				case 7 -> HINT_MAP;
				case 8 -> HINT_DRAGGING;
				case 9 -> HINT_SIDEBAR;
				case 10 -> HINT_SYNCING;
				default -> HINT_IDLE;
			};
		}
		return this.hint;
	}

	private void renderPalettePopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int count = RegionColor.paletteCount();
		int w = count * SWATCH_STRIDE + 6;
		int h = SWATCH_SIZE + 8;
		int x = this.paletteX(w);
		int y = this.barY - h - 2;
		graphics.fill(x, y, x + w, y + h, 0xF60F151D);
		graphics.outline(x, y, w, h, ACCENT_ZONES);
		RegionColor active = this.chunkMapWidget.activeColor();
		for (int i = 0; i < count; i++) {
			RegionColor color = RegionColor.paletteAt(i);
			int sx = x + 4 + i * SWATCH_STRIDE;
			int sy = y + 4;
			if (color == active) {
				graphics.outline(sx - 2, sy - 2, SWATCH_SIZE + 4, SWATCH_SIZE + 4, 0xFFFFFFFF);
			}
			graphics.fill(sx, sy, sx + SWATCH_SIZE, sy + SWATCH_SIZE, color.swatchArgb());
			graphics.outline(sx, sy, SWATCH_SIZE, SWATCH_SIZE, this.contains(sx, sy, SWATCH_SIZE, SWATCH_SIZE, mouseX, mouseY) ? TEXT_PRIMARY : 0xFF000000);
			graphics.text(this.font, color.hotkeyLabel(), sx + 3, sy + 1, color.prefersDarkText() ? 0xFF10161D : 0xFFFFFFFF, false);
		}
	}

	private int paletteX(int popupWidth) {
		return Mth.clamp(this.paletteMoreButton.x - popupWidth / 2, this.barX, this.barX + this.barW - popupWidth);
	}

	private RegionColor paletteColorAt(double mouseX, double mouseY) {
		int count = RegionColor.paletteCount();
		int w = count * SWATCH_STRIDE + 6;
		int h = SWATCH_SIZE + 8;
		int x = this.paletteX(w);
		int y = this.barY - h - 2;
		if (!this.contains(x, y, w, h, mouseX, mouseY)) {
			return null;
		}
		int index = (int) ((mouseX - x - 2) / SWATCH_STRIDE);
		return index >= 0 && index < count ? RegionColor.paletteAt(index) : null;
	}

	private void handlePaletteClick(double mouseX, double mouseY) {
		RegionColor color = this.paletteColorAt(mouseX, mouseY);
		if (color != null) {
			this.selectColor(color);
		}
		this.paletteOpen = false;
	}

	private int followMenuItemHeight() {
		return 12;
	}

	private int followMenuVisibleCount() {
		return Math.min(this.viewGroupCount, Math.max(1, (this.barY - this.panelY - 20) / this.followMenuItemHeight()));
	}

	private void renderFollowMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int itemH = this.followMenuItemHeight();
		int count = this.followMenuVisibleCount();
		this.followMenuOffset = Mth.clamp(this.followMenuOffset, 0, Math.max(0, this.viewGroupCount - count));
		int w = Math.max(this.followGroupButton.w, 100);
		int x = Math.min(this.followGroupButton.x, this.barX + this.barW - w);
		int h = count * itemH + 4;
		int y = this.followGroupButton.y - h - 2;
		graphics.fill(x, y, x + w, y + h, 0xF60F151D);
		graphics.outline(x, y, w, h, ACCENT_GROUPS);
		ClientTacticsDataStore.WorldContext context = this.worldContext();
		for (int item = 0; item < count; item++) {
			int row = this.followMenuOffset + item;
			int iy = y + 2 + item * itemH;
			boolean hovered = this.contains(x + 1, iy, w - 2, itemH, mouseX, mouseY);
			if (hovered || row == this.orderGroupRow) {
				graphics.fill(x + 1, iy, x + w - 1, iy + itemH, hovered ? HEADER_ROW_HOVER_BACKGROUND : HEADER_ROW_OPEN_BACKGROUND);
			}
			RegionColor color = context == null ? RegionColor.NONE : this.dataStore.getGroupColor(context, row);
			graphics.fill(x + 3, iy + 2, x + 6, iy + itemH - 2, color == RegionColor.NONE ? NO_COLOR_STRIPE : color.swatchArgb());
			graphics.text(this.font, this.fitGroupName(row, w - 14), x + 9, iy + 2, TEXT_PRIMARY, false);
		}
	}

	private void handleFollowMenuClick(double mouseX, double mouseY) {
		int itemH = this.followMenuItemHeight();
		int count = this.followMenuVisibleCount();
		this.followMenuOffset = Mth.clamp(this.followMenuOffset, 0, Math.max(0, this.viewGroupCount - count));
		int w = Math.max(this.followGroupButton.w, 100);
		int x = Math.min(this.followGroupButton.x, this.barX + this.barW - w);
		int h = count * itemH + 4;
		int y = this.followGroupButton.y - h - 2;
		this.followMenuOpen = false;
		if (!this.contains(x, y, w, h, mouseX, mouseY)) {
			return;
		}
		int item = (int) Math.floor((mouseY - y - 2) / itemH);
		if (item >= 0 && item < count) {
			int row = this.followMenuOffset + item;
			this.orderGroupRow = row;
			this.orderGroup(row, true);
		}
	}

	private static final int GUARD_MENU_WIDTH = 96;
	private static final int GUARD_MENU_ITEM_HEIGHT = 12;

	/** Menu box {x, y, w, h}: opens at the click, flipped and clamped so it stays inside the panel. */
	private int[] guardMenuBox() {
		int h = 13 + 2 * GUARD_MENU_ITEM_HEIGHT + 3;
		int w = GUARD_MENU_WIDTH;
		int x = this.guardMenuX + w > this.panelX + this.panelWidth - 2 ? this.guardMenuX - w : this.guardMenuX;
		int y = this.guardMenuY + h > this.panelY + this.panelHeight - 2 ? this.guardMenuY - h : this.guardMenuY;
		return new int[] {Math.max(this.panelX + 2, x), Math.max(this.panelY + 2, y), w, h};
	}

	/** Whether item 0 (make commander) or 1 (make captain) applies to the guard the menu was opened on. */
	private static boolean guardMenuItemEnabled(ClientGuardRosterStore.GuardRosterEntry guard, int group, int item) {
		if (guard == null || !guard.loaded()) {
			return false;
		}
		return item == 0 ? guard.rank() != 2 : guard.rank() != 1 && group >= 0;
	}

	private void renderGuardMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int[] box = this.guardMenuBox();
		int x = box[0];
		int y = box[1];
		int w = box[2];
		graphics.fill(x, y, x + w, y + box[3], 0xF60F151D);
		graphics.outline(x, y, w, box[3], ACCENT_GROUPS);
		graphics.text(this.font, ellipsize(this.font, this.guardMenuGuard.displayName(), w - 8), x + 4, y + 3, TEXT_MUTED, false);
		for (int item = 0; item < 2; item++) {
			int iy = y + 13 + item * GUARD_MENU_ITEM_HEIGHT;
			boolean enabled = guardMenuItemEnabled(this.guardMenuGuard, this.guardMenuGroup, item);
			if (enabled && this.contains(x + 1, iy, w - 2, GUARD_MENU_ITEM_HEIGHT, mouseX, mouseY)) {
				graphics.fill(x + 1, iy, x + w - 1, iy + GUARD_MENU_ITEM_HEIGHT, HEADER_ROW_HOVER_BACKGROUND);
			}
			this.drawRankBadge(graphics, item == 0 ? 2 : 1, x + 4, iy + 3);
			graphics.text(this.font, item == 0 ? MENU_MAKE_COMMANDER : MENU_MAKE_CAPTAIN, x + 12, iy + 2, enabled ? TEXT_PRIMARY : TEXT_MUTED, false);
		}
		this.drawnBadgeX = Integer.MIN_VALUE;
	}

	private void handleGuardMenuClick(double mouseX, double mouseY) {
		ClientGuardRosterStore.GuardRosterEntry guard = this.guardMenuGuard;
		int group = this.guardMenuGroup;
		int[] box = this.guardMenuBox();
		this.guardMenuGuard = null;
		int item = (int) Math.floor((mouseY - box[1] - 13) / GUARD_MENU_ITEM_HEIGHT);
		if (!this.contains(box[0], box[1], box[2], box[3], mouseX, mouseY) || item < 0 || item > 1
			|| !guardMenuItemEnabled(guard, group, item)) {
			return;
		}
		boolean commander = item == 0;
		this.sendOrder(
			"guards appoint " + (commander ? "commander " : "captain ") + guard.guardUuid(),
			Component.translatable(KEY + (commander ? "status.appoint_commander" : "status.appoint_captain"), guard.displayName())
		);
	}

	// ---------------------------------------------------------------- orders

	private void orderGroup(int row, boolean follow) {
		if (row < 0 || row >= this.viewGroupCount) {
			return;
		}
		String name = this.groupNames.get(row);
		// Row identity stays unambiguous even when display names collide.
		this.sendOrder(
			(follow ? "guards groups follow " : "guards groups stay ") + (row + 1),
			Component.translatable(KEY + (follow ? "status.follow_group" : "status.stay_group"), name)
		);
	}

	private void sendOrder(String command, Component status) {
		if (this.sendCommand(command)) {
			this.setStatus(status, STATUS_SUCCESS);
		}
	}

	// ---------------------------------------------------------------- sidebar

	private void refreshRosterView(ClientTacticsDataStore.WorldContext context) {
		int rosterRevision = this.rosterStore.revision();
		if (rosterRevision == this.viewRosterRevision
			&& this.localRevision == this.viewLocalRevision
			&& this.sortMode == this.viewSort
			&& context.equals(this.viewContext)) {
			return;
		}
		if (rosterRevision != this.viewRosterRevision) {
			this.cardTexts.clear();
			this.tooltipGuard = null;
		}
		this.viewRosterRevision = rosterRevision;
		this.viewLocalRevision = this.localRevision;
		this.viewSort = this.sortMode;
		this.viewContext = context;

		List<ClientGuardRosterStore.GuardRosterEntry> roster = this.rosterStore.roster(context);
		this.viewRosterLoaded = this.rosterStore.hasRoster(context);
		this.viewRosterSize = roster.size();
		// The server rejects rows it does not have, so only show rows from the synced list (plus rows this
		// screen asked it to add). Guards holding a stale index beyond that are listed as unassigned.
		int groupCount = Mth.clamp(this.dataStore.groupCount(context), 0, ClientTacticsDataStore.MAX_GROUPS);
		this.viewGroupCount = groupCount;

		while (this.groupMembers.size() < groupCount) {
			this.groupMembers.add(new ArrayList<>());
		}
		for (List<ClientGuardRosterStore.GuardRosterEntry> members : this.groupMembers) {
			members.clear();
		}
		this.unassignedList.clear();
		this.groupAllFollowing.clear();
		this.groupAllStaying.clear();
		this.groupAllFollowing.set(0, groupCount);
		this.groupAllStaying.set(0, groupCount);
		BitSet anyLoaded = new BitSet();
		for (ClientGuardRosterStore.GuardRosterEntry guard : roster) {
			// The roster is re-sent about once a second; a pending move the server now matches is no longer a change.
			Integer pending = this.pendingAssignments.get(guard.guardUuid());
			if (pending != null && pending == guard.groupIndex() && !this.awaitingConfirmation.containsKey(guard.guardUuid())) {
				this.pendingAssignments.remove(guard.guardUuid());
			}
			int group = this.displayGroup(guard);
			if (group >= 0 && guard.loaded()) {
				anyLoaded.set(group);
				if (!guard.following()) {
					this.groupAllFollowing.clear(group);
				}
				if (!guard.staying()) {
					this.groupAllStaying.clear(group);
				}
			}
			if (group >= 0) {
				this.groupMembers.get(group).add(guard);
			} else {
				this.unassignedList.add(guard);
			}
		}
		this.groupAllFollowing.and(anyLoaded);
		this.groupAllStaying.and(anyLoaded);
		Comparator<ClientGuardRosterStore.GuardRosterEntry> sorter = this.sortMode.comparator;
		for (int row = 0; row < groupCount; row++) {
			this.groupMembers.get(row).sort(sorter);
		}
		this.unassignedList.sort(sorter);

		this.groupNames.clear();
		for (int row = 0; row < groupCount; row++) {
			this.groupNames.add(this.resolveGroupName(context, row, this.groupMembers.get(row)));
		}
		if (this.orderGroupRow < 0 && groupCount > 0) {
			this.orderGroupRow = 0;
		}
		this.updateFollowGroupLabel();
	}

	private void renderSidebar(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		int x = this.sideX;
		int y = this.sideY;
		int w = this.sideW;
		graphics.fill(x, y, x + w, y + this.sideH, SIDEBAR_BACKGROUND);
		graphics.outline(x, y, w, this.sideH, PANEL_BORDER);
		graphics.text(this.font, GROUPS_HEADER, x + 4, y + 3, ACCENT_GROUPS, false);
		this.addGroupButton.place(x + w - 3 - this.addGroupButton.w, y + 2, 10);
		boolean canCreate = this.viewGroupCount < ClientTacticsDataStore.MAX_GROUPS;
		this.addGroupButton.enabled = canCreate && worldContext != null;
		this.addGroupButton.tooltip = canCreate ? TOOLTIP_ADD_GROUP : this.groupLimitTooltip();
		this.drawButton(graphics, this.addGroupButton, mouseX, mouseY, STYLE_DARK);
		this.sortButton.place(this.addGroupButton.x - 3 - this.sortButton.w, y + 2, 10);
		this.sortButton.visible = this.sortButton.x > x + 4 + this.font.width(GROUPS_HEADER) + 4;
		this.drawButton(graphics, this.sortButton, mouseX, mouseY, STYLE_DARK);

		if (worldContext == null) {
			this.renderCenteredMessage(graphics, UNAVAILABLE, null, x, y + 14, w, 40);
			return;
		}
		if (this.editingRow >= this.viewGroupCount) {
			this.cancelGroupRename();
		}

		this.listTop = y + 14;
		this.listBottom = y + this.sideH - 2;
		int listX = x + 2;
		int listW = w - 4 - SCROLLBAR_SPACE;
		int listH = this.listBottom - this.listTop;
		boolean dragging = this.dragHandler.isActive();
		boolean showNewGroup = this.viewGroupCount == 0 || (dragging && canCreate);
		int newGroupH = this.viewGroupCount == 0 ? 30 : GROUP_HEADER_HEIGHT;

		if (!this.autoCollapseChecked && this.viewRosterLoaded) {
			this.autoCollapseChecked = true;
			this.autoCollapse(listW, listH);
		}
		int total = 0;
		for (int row = 0; row < this.viewGroupCount; row++) {
			total += this.groupBlockHeight(row, listW) + GROUP_GAP;
		}
		if (showNewGroup) {
			total += newGroupH + GROUP_GAP;
		}
		total += 11 + this.trayHeight(listW);
		this.listMaxScroll = Math.max(0, total - listH);
		this.autoScrollWhileDragging(mouseX, mouseY);
		this.listScrollTarget = Mth.clamp(this.listScrollTarget, 0, this.listMaxScroll);
		this.listScrollAnimated = lerpPaneScroll(this.listScrollAnimated, this.listScrollTarget);
		int scroll = Mth.clamp((int) Math.round(this.listScrollAnimated), 0, this.listMaxScroll);

		graphics.enableScissor(x + 1, this.listTop, x + w - 1, this.listBottom);
		int cy = this.listTop - scroll;
		for (int row = 0; row < this.viewGroupCount; row++) {
			int h = this.groupBlockHeight(row, listW);
			if (cy + h >= this.listTop && cy <= this.listBottom) {
				this.renderGroupBlock(graphics, worldContext, row, listX, cy, listW, h, mouseX, mouseY);
			} else if (this.editingRow == row && this.groupRenameField != null) {
				this.groupRenameField.setVisible(false);
			}
			cy += h + GROUP_GAP;
		}
		if (showNewGroup) {
			this.renderNewGroupDrop(graphics, listX, cy, listW, newGroupH);
			cy += newGroupH + GROUP_GAP;
		}
		this.renderTray(graphics, listX, cy, listW, mouseX, mouseY);
		graphics.disableScissor();

		if (this.listMaxScroll > 0) {
			this.renderScrollbar(graphics, x + w - 4, this.listTop, 3, listH, scroll, this.listMaxScroll, total);
			// Fade plus arrows make it obvious there is more list than fits (round-1 screenshots hid a group).
			int arrowX = x + (w - SCROLLBAR_SPACE) / 2;
			if (scroll < this.listMaxScroll) {
				graphics.fillGradient(x + 1, this.listBottom - 10, x + w - SCROLLBAR_SPACE, this.listBottom, 0x000E131A, 0xCC0E131A);
				this.drawArrow(graphics, arrowX, this.listBottom - 5, true, KEY_COLOR);
			}
			if (scroll > 0) {
				graphics.fillGradient(x + 1, this.listTop, x + w - SCROLLBAR_SPACE, this.listTop + 8, 0xCC0E131A, 0x000E131A);
				this.drawUpArrow(graphics, arrowX, this.listTop + 2, KEY_COLOR);
			}
		}
	}

	// On small windows, start with every group but the first collapsed to chips so the list fits.
	private void autoCollapse(int listW, int listH) {
		int expanded = 0;
		for (int row = 0; row < this.viewGroupCount; row++) {
			expanded += this.groupBlockHeight(row, listW) + GROUP_GAP;
		}
		expanded += 11 + this.trayHeight(listW);
		if (expanded > listH) {
			for (int row = 1; row < this.viewGroupCount; row++) {
				this.collapsedRows.set(row);
			}
		}
	}

	private void drawUpArrow(GuiGraphicsExtractor graphics, int x, int y, int color) {
		graphics.fill(x, y, x + 1, y + 1, color);
		graphics.fill(x - 1, y + 1, x + 2, y + 2, color);
		graphics.fill(x - 2, y + 2, x + 3, y + 3, color);
	}

	private int groupBlockHeight(int row, int listW) {
		int members = this.groupMembers.get(row).size();
		if (this.collapsedRows.get(row)) {
			return GROUP_HEADER_HEIGHT + 1 + this.chipRowsHeight(members, listW);
		}
		return GROUP_HEADER_HEIGHT + 1 + (members == 0 ? MEMBER_ROW_HEIGHT : members * MEMBER_ROW_STRIDE);
	}

	private int chipsPerRow(int listW) {
		return Math.max(1, (listW - 2 + CHIP_GAP) / (CHIP_WIDTH + CHIP_GAP));
	}

	private int chipRowsHeight(int members, int listW) {
		if (members == 0) {
			return CHIP_HEIGHT + 2;
		}
		int rows = (members + this.chipsPerRow(listW) - 1) / this.chipsPerRow(listW);
		return rows * (CHIP_HEIGHT + CHIP_GAP);
	}

	private int trayPerRow(int listW) {
		return Math.max(1, (listW - 4 + CHIP_GAP) / (TRAY_CHIP_MIN_WIDTH + CHIP_GAP));
	}

	private int trayChipWidth(int listW) {
		int perRow = this.trayPerRow(listW);
		return Math.max(TRAY_CHIP_MIN_WIDTH, (listW - 4 - (perRow - 1) * CHIP_GAP) / perRow);
	}

	private int trayHeight(int listW) {
		int n = this.unassignedList.size();
		if (n == 0) {
			return 18;
		}
		int rows = (n + this.trayPerRow(listW) - 1) / this.trayPerRow(listW);
		return Math.max(18, rows * (TRAY_CHIP_HEIGHT + CHIP_GAP) + 3);
	}

	private void renderGroupBlock(
		GuiGraphicsExtractor graphics,
		ClientTacticsDataStore.WorldContext worldContext,
		int row,
		int x,
		int y,
		int w,
		int h,
		int mouseX,
		int mouseY
	) {
		boolean dragging = this.dragHandler.isActive();
		boolean collapsed = this.collapsedRows.get(row);
		boolean dropHovered = dragging && this.dragTarget != null && this.dragTarget.kind == DropKind.GROUP && this.dragTarget.group == row;
		boolean inList = this.inList(mouseY);
		boolean headerHovered = !dragging && inList && this.contains(x, y, w, GROUP_HEADER_HEIGHT, mouseX, mouseY);

		if (dropHovered) {
			graphics.fill(x, y, x + w, y + h, DROP_HOVER_BACKGROUND);
		}
		// Header row
		graphics.fill(x, y, x + w, y + GROUP_HEADER_HEIGHT, headerHovered ? HEADER_ROW_HOVER_BACKGROUND : collapsed ? HEADER_ROW_BACKGROUND : HEADER_ROW_OPEN_BACKGROUND);
		graphics.outline(x, y, w, GROUP_HEADER_HEIGHT, dropHovered ? DROP_HOVER_BORDER : dragging ? DROP_IDLE_BORDER : ROW_BORDER);
		RegionColor groupColor = this.dataStore.getGroupColor(worldContext, row);
		boolean stripeHovered = !dragging && inList && this.contains(x, y, 6, GROUP_HEADER_HEIGHT, mouseX, mouseY);
		graphics.fill(x, y, x + (stripeHovered ? 5 : 3), y + GROUP_HEADER_HEIGHT, groupColor == RegionColor.NONE ? NO_COLOR_STRIPE : groupColor.swatchArgb());
		this.drawArrow(graphics, x + 9, y + 5, !collapsed, TEXT_SECONDARY);

		List<ClientGuardRosterStore.GuardRosterEntry> members = this.groupMembers.get(row);
		boolean compact = w < 150;
		Component followLabel = compact ? FOLLOW_SHORT : FOLLOW;
		Component stayLabel = compact ? STAY_SHORT : STAY;
		int stayW = this.font.width(stayLabel) + 6;
		int followW = this.font.width(followLabel) + 6;
		int stayX = x + w - 2 - stayW;
		int followX = stayX - 2 - followW;
		int buttonY = y + 1;
		this.drawSmallButton(graphics, stayLabel, stayX, buttonY, stayW, inList && !dragging && this.contains(stayX, buttonY, stayW, SMALL_BUTTON_HEIGHT, mouseX, mouseY), this.groupAllStaying.get(row));
		this.drawSmallButton(graphics, followLabel, followX, buttonY, followW, inList && !dragging && this.contains(followX, buttonY, followW, SMALL_BUTTON_HEIGHT, mouseX, mouseY), this.groupAllFollowing.get(row));
		String count = this.countText(members.size());
		int countX = followX - 3 - this.font.width(count);
		graphics.text(this.font, count, countX, y + 3, TEXT_SECONDARY, false);

		int nameX = x + 15;
		if (this.editingRow == row && this.groupRenameField != null) {
			this.groupRenameField.setVisible(true);
			this.groupRenameField.setX(nameX - 2);
			this.groupRenameField.setY(y + 1);
			this.groupRenameField.setWidth(Math.max(30, countX - 2 - (nameX - 2)));
		} else {
			graphics.text(this.font, this.fitGroupName(row, countX - 4 - nameX), nameX, y + 3, TEXT_PRIMARY, false);
		}
		this.addRowHit(row, x, y, w, GROUP_HEADER_HEIGHT, followX, followW, stayX, stayW);
		this.addDropHit(DropKind.GROUP, row, x, y, w, h);

		int bodyY = y + GROUP_HEADER_HEIGHT + 1;
		if (members.isEmpty()) {
			String empty = this.newGroupFit.fit(this.font, GROUP_EMPTY, w - 8);
			graphics.text(this.font, empty, x + 6, bodyY + 3, TEXT_MUTED, false);
			return;
		}
		if (collapsed) {
			int perRow = this.chipsPerRow(w);
			for (int i = Math.max(0, (this.listTop - bodyY) / (CHIP_HEIGHT + CHIP_GAP)) * perRow;
					i < Math.min(members.size(), (Math.max(0, (this.listBottom - bodyY) / (CHIP_HEIGHT + CHIP_GAP)) + 1) * perRow); i++) {
				int cx = x + 1 + (i % perRow) * (CHIP_WIDTH + CHIP_GAP);
				int cyy = bodyY + (i / perRow) * (CHIP_HEIGHT + CHIP_GAP);
				ClientGuardRosterStore.GuardRosterEntry guard = members.get(i);
				this.renderChip(graphics, guard, cx, cyy, mouseX, mouseY);
				this.addCardHit(guard, cx, cyy, CHIP_WIDTH, CHIP_HEIGHT, row);
			}
			return;
		}
		for (int i = Math.max(0, (this.listTop - bodyY) / MEMBER_ROW_STRIDE);
				i < Math.min(members.size(), Math.max(0, (this.listBottom - bodyY) / MEMBER_ROW_STRIDE + 1)); i++) {
			int ry = bodyY + i * MEMBER_ROW_STRIDE;
			if (ry > this.listBottom || ry + MEMBER_ROW_HEIGHT < this.listTop) {
				continue;
			}
			ClientGuardRosterStore.GuardRosterEntry guard = members.get(i);
			this.renderMemberRow(graphics, guard, x + 2, ry, w - 2, mouseX, mouseY);
			this.addCardHit(guard, x + 2, ry, w - 2, MEMBER_ROW_HEIGHT, row);
		}
	}

	private void renderNewGroupDrop(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		boolean dragging = this.dragHandler.isActive();
		boolean hovered = dragging && this.dragTarget != null && this.dragTarget.kind == DropKind.NEW_GROUP;
		graphics.fill(x, y, x + w, y + h, hovered ? DROP_HOVER_BACKGROUND : DROP_ZONE_BACKGROUND);
		graphics.outline(x, y, w, h, hovered ? DROP_HOVER_BORDER : DROP_IDLE_BORDER);
		if (this.viewGroupCount == 0) {
			this.renderCenteredMessage(graphics, GROUPS_NONE_TITLE, GROUPS_NONE_BODY, x, y, w, h);
		} else {
			graphics.text(this.font, this.newGroupFit.fit(this.font, GROUP_NEW, w - 8), x + 4, y + 3, TEXT_SECONDARY, false);
		}
		this.addDropHit(DropKind.NEW_GROUP, this.viewGroupCount, x, y, w, h);
	}

	private void renderTray(GuiGraphicsExtractor graphics, int x, int y, int w, int mouseX, int mouseY) {
		int n = this.unassignedList.size();
		if (n != this.trayLabelCount) {
			this.trayLabelCount = n;
			this.trayLabel = Component.translatable(KEY + "tray.header", n).getString();
		}
		graphics.text(this.font, this.trayLabel, x + 2, y + 1, TEXT_MUTED, false);
		int trayY = y + 11;
		int trayH = this.trayHeight(w);
		boolean dragging = this.dragHandler.isActive();
		boolean hovered = dragging && this.dragTarget != null && this.dragTarget.kind == DropKind.UNASSIGN;
		graphics.fill(x, trayY, x + w, trayY + trayH, hovered ? DROP_HOVER_BACKGROUND : DROP_ZONE_BACKGROUND);
		this.drawDashedOutline(graphics, x, trayY, w, trayH, hovered ? DROP_HOVER_BORDER : dragging ? KEY_COLOR : DROP_IDLE_BORDER);
		this.addDropHit(DropKind.UNASSIGN, -1, x, trayY, w, trayH);

		if (n == 0) {
			Component message;
			if (!this.viewRosterLoaded) {
				message = GUARDS_SYNCING;
			} else if (this.viewRosterSize == 0) {
				message = null;
			} else {
				message = TRAY_EMPTY;
			}
			if (message != null) {
				graphics.text(this.font, this.trayFit.fit(this.font, message, w - 8), x + 4, trayY + 5, TEXT_MUTED, false);
			} else {
				// No guards at all: point at the shop instead of an empty tray.
				graphics.text(this.font, this.trayFit.fit(this.font, GUARDS_EMPTY_BODY, w - 8), x + 4, trayY + 5, TEXT_SECONDARY, false);
			}
			return;
		}
		int perRow = this.trayPerRow(w);
		int chipW = this.trayChipWidth(w);
		for (int i = Math.max(0, (this.listTop - trayY - 2) / (TRAY_CHIP_HEIGHT + CHIP_GAP)) * perRow;
				i < Math.min(n, (Math.max(0, (this.listBottom - trayY - 2) / (TRAY_CHIP_HEIGHT + CHIP_GAP)) + 1) * perRow); i++) {
			int cx = x + 2 + (i % perRow) * (chipW + CHIP_GAP);
			int cyy = trayY + 2 + (i / perRow) * (TRAY_CHIP_HEIGHT + CHIP_GAP);
			ClientGuardRosterStore.GuardRosterEntry guard = this.unassignedList.get(i);
			this.renderTrayChip(graphics, guard, cx, cyy, chipW, mouseX, mouseY);
			this.addCardHit(guard, cx, cyy, chipW, TRAY_CHIP_HEIGHT, -1);
		}
	}

	/**
	 * One guard in an expanded group. The name gets the room first: from the right, the order letter, level number
	 * and a short health bar are fixed; the distance only shows when the full name still fits beside it.
	 */
	private void renderMemberRow(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int w, int mouseX, int mouseY) {
		boolean hovered = this.cardHovered(x, y, w, MEMBER_ROW_HEIGHT, mouseX, mouseY);
		graphics.fill(x, y, x + w, y + MEMBER_ROW_HEIGHT, hovered ? CARD_HOVER_BACKGROUND : CARD_BACKGROUND);
		graphics.outline(x, y, w, MEMBER_ROW_HEIGHT, hovered ? CARD_HOVER_BORDER : ROW_BORDER);
		this.drawPendingMarker(graphics, guard, x, y, MEMBER_ROW_HEIGHT);
		this.drawSmallItem(graphics, roleIcon(guard), x + 2, y + 3);
		int nameX = x + 12;
		if (this.drawRankBadge(graphics, guard.rank(), nameX, y + 4)) {
			nameX += RANK_BADGE_SIZE + 2;
		}

		CardText text = this.cardText(guard);
		if (!guard.loaded()) {
			// Unloaded guards only have a name and group; show that plainly instead of empty stats.
			String notLoaded = text.notLoaded(this.font);
			int notLoadedWidth = this.font.width(notLoaded);
			graphics.text(this.font, notLoaded, x + w - 3 - notLoadedWidth, y + 3, TEXT_MUTED, false);
			graphics.text(this.font, text.rowName(this.font, x + w - 6 - notLoadedWidth - nameX), nameX, y + 3, TEXT_MUTED, false);
			graphics.fill(x, y, x + w, y + MEMBER_ROW_HEIGHT, 0x660E141B);
			this.dimIfDragSource(graphics, guard, x, y, w, MEMBER_ROW_HEIGHT);
			return;
		}
		String badge = orderBadge(guard);
		int badgeWidth = badge.isEmpty() ? 0 : this.font.width(badge) + 3;
		int levelWidth = this.font.width(text.levelNumber);
		int nameRoom = x + w - 3 - levelWidth - 3 - badgeWidth - nameX - 3;
		// Health bar: 16px, down to 8px when the name would otherwise be shortened.
		int barW = nameRoom - 19 >= text.nameWidth ? 16 : 8;
		nameRoom -= barW + 3;
		boolean showDistance = !text.distance.isEmpty() && nameRoom - text.distanceWidth - 4 >= text.nameWidth;

		int right = x + w - 3;
		if (showDistance) {
			graphics.text(this.font, text.distance, right - text.distanceWidth, y + 3, TEXT_MUTED, false);
			right -= text.distanceWidth + 4;
		}
		this.drawHealthBar(graphics, guard, right - barW, y + 6, barW, 2);
		right -= barW + 3;
		graphics.text(this.font, text.levelNumber, right - levelWidth, y + 3, LEVEL_COLOR, false);
		right -= levelWidth + 3;
		// One-letter order badge: F follow, S stay, H hold (full words in the tooltip).
		if (!badge.isEmpty()) {
			graphics.text(this.font, badge, right - badgeWidth + 3, y + 3, orderColor(guard), false);
			right -= badgeWidth;
		}
		graphics.text(this.font, text.rowName(this.font, right - nameX), nameX, y + 3, TEXT_PRIMARY, false);
		this.dimIfDragSource(graphics, guard, x, y, w, MEMBER_ROW_HEIGHT);
	}

	// Collapsed group chip: role icon, level and rank badge on top, the health bar along the bottom edge.
	private void renderChip(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int mouseX, int mouseY) {
		boolean hovered = this.cardHovered(x, y, CHIP_WIDTH, CHIP_HEIGHT, mouseX, mouseY);
		graphics.fill(x, y, x + CHIP_WIDTH, y + CHIP_HEIGHT, hovered ? CARD_HOVER_BACKGROUND : CARD_BACKGROUND);
		graphics.outline(x, y, CHIP_WIDTH, CHIP_HEIGHT, hovered ? CARD_HOVER_BORDER : ROW_BORDER);
		this.drawPendingMarker(graphics, guard, x, y, CHIP_HEIGHT);
		this.drawSmallItem(graphics, roleIcon(guard), x + 2, y + 1);
		graphics.text(this.font, guard.loaded() ? this.cardText(guard).levelNumber : "?", x + 12, y + 1, guard.loaded() ? LEVEL_COLOR : TEXT_MUTED, false);
		this.drawRankBadge(graphics, guard.rank(), x + CHIP_WIDTH - 3 - RANK_BADGE_SIZE, y + 2);
		this.drawHealthBar(graphics, guard, x + 2, y + CHIP_HEIGHT - 2, CHIP_WIDTH - 4, 1);
		if (!guard.loaded()) {
			graphics.fill(x, y, x + CHIP_WIDTH, y + CHIP_HEIGHT, 0x660E141B);
		}
		this.dimIfDragSource(graphics, guard, x, y, CHIP_WIDTH, CHIP_HEIGHT);
	}

	private void renderTrayChip(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int w, int mouseX, int mouseY) {
		boolean hovered = this.cardHovered(x, y, w, TRAY_CHIP_HEIGHT, mouseX, mouseY);
		graphics.fill(x, y, x + w, y + TRAY_CHIP_HEIGHT, hovered ? CARD_HOVER_BACKGROUND : CARD_BACKGROUND);
		graphics.outline(x, y, w, TRAY_CHIP_HEIGHT, hovered ? CARD_HOVER_BORDER : ROW_BORDER);
		this.drawPendingMarker(graphics, guard, x, y, TRAY_CHIP_HEIGHT);
		this.drawSmallItem(graphics, roleIcon(guard), x + 2, y + 2);
		int nameX = x + 11;
		if (this.drawRankBadge(graphics, guard.rank(), nameX, y + 3)) {
			nameX += RANK_BADGE_SIZE + 2;
		}
		CardText text = this.cardText(guard);
		int levelWidth = guard.loaded() ? this.font.width(text.levelNumber) : 0;
		if (guard.loaded()) {
			graphics.text(this.font, text.levelNumber, x + w - 3 - levelWidth, y + 3, LEVEL_COLOR, false);
		}
		graphics.text(this.font, text.miniName(this.font, x + w - 5 - levelWidth - nameX), nameX, y + 3, guard.loaded() ? TEXT_PRIMARY : TEXT_MUTED, false);
		this.drawHealthBar(graphics, guard, x + 2, y + TRAY_CHIP_HEIGHT - 2, w - 4, 1);
		if (!guard.loaded()) {
			graphics.fill(x, y, x + w, y + TRAY_CHIP_HEIGHT, 0x660E141B);
		}
		this.dimIfDragSource(graphics, guard, x, y, w, TRAY_CHIP_HEIGHT);
	}

	/** Draws the rank badge with its top-left at (x, y); returns false (drawing nothing) for soldiers. */
	private boolean drawRankBadge(GuiGraphicsExtractor graphics, int rank, int x, int y) {
		String[] pixels = rank == 2 ? STAR_PIXELS : rank == 1 ? CHEVRON_PIXELS : null;
		if (pixels == null) {
			return false;
		}
		int color = rank == 2 ? RANK_COMMANDER_COLOR : RANK_CAPTAIN_COLOR;
		for (int pass = 0; pass < 2; pass++) {
			// First pass: a 1px drop shadow so the badge reads on hovered and plain cards alike.
			int offset = pass == 0 ? 1 : 0;
			int fill = pass == 0 ? RANK_SHADOW : color;
			for (int row = 0; row < pixels.length; row++) {
				String line = pixels[row];
				for (int col = 0; col < line.length(); col++) {
					if (line.charAt(col) == '#') {
						graphics.fill(x + col + offset, y + row + offset, x + col + offset + 1, y + row + offset + 1, fill);
					}
				}
			}
		}
		this.drawnBadgeX = x;
		this.drawnBadgeY = y;
		return true;
	}

	private boolean cardHovered(int x, int y, int w, int h, int mouseX, int mouseY) {
		return !this.dragHandler.isActive() && this.inList(mouseY) && this.contains(x, y, w, h, mouseX, mouseY);
	}

	private void drawPendingMarker(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int h) {
		if (this.pendingAssignments.containsKey(guard.guardUuid())) {
			graphics.fill(x + 1, y + 1, x + 2, y + h - 1, PENDING_MARKER);
		}
	}

	private void dimIfDragSource(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int w, int h) {
		if (this.dragHandler.isActive() && this.dragHandler.draggedGuard() == guard) {
			graphics.fill(x, y, x + w, y + h, 0xAA0E141B);
		}
	}

	/** Role icon from the roster's GuardRole name; unknown roles show a sword. */
	private static ItemStack roleIcon(ClientGuardRosterStore.GuardRosterEntry guard) {
		return "BOWMAN".equals(guard.role()) ? BOW_ICON : SWORD_ICON;
	}

	private static String orderBadge(ClientGuardRosterStore.GuardRosterEntry guard) {
		if (guard.holding()) {
			return "H";
		}
		if (guard.staying()) {
			return "S";
		}
		if (guard.following()) {
			return "F";
		}
		return "";
	}

	private static int orderColor(ClientGuardRosterStore.GuardRosterEntry guard) {
		if (guard.holding()) {
			return 0xFFFFA65C;
		}
		return guard.staying() ? KEY_COLOR : STATUS_SUCCESS;
	}

	private static String orderKey(ClientGuardRosterStore.GuardRosterEntry guard) {
		if (guard.holding()) {
			return "holding";
		}
		if (guard.staying()) {
			return "staying";
		}
		return guard.following() ? "following" : "free";
	}

	/** "CROWD_CONTROL" -> lang key with a readable fallback ("Crowd Control"). */
	private static MutableComponent enumText(String kind, String enumName) {
		String lower = enumName.toLowerCase(Locale.ROOT);
		return Component.translatableWithFallback(KEY + kind + "." + lower, prettyDimension(lower));
	}

	private void drawSmallItem(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		graphics.pose().pushMatrix();
		graphics.pose().translate((float) x, (float) y);
		graphics.pose().scale(0.5F, 0.5F);
		graphics.item(stack, 0, 0);
		graphics.pose().popMatrix();
	}

	private void drawHealthBar(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int w, int h) {
		if (guard.maxHealth() <= 0.0F || w <= 0) {
			return;
		}
		float ratio = Mth.clamp(guard.health() / guard.maxHealth(), 0.0F, 1.0F);
		int color = ratio > 0.6F ? 0xFF4CC26A : ratio > 0.3F ? 0xFFE0B341 : 0xFFE05252;
		graphics.fill(x, y, x + w, y + h, 0xFF3A0D0D);
		graphics.fill(x, y, x + Math.max(1, Math.round(w * ratio)), y + h, color);
	}

	private void drawSmallButton(GuiGraphicsExtractor graphics, Component label, int x, int y, int w, boolean hovered, boolean on) {
		int[] style = on ? STYLE_ON : STYLE_DARK;
		graphics.fill(x, y, x + w, y + SMALL_BUTTON_HEIGHT, hovered ? style[1] : style[0]);
		graphics.outline(x, y, w, SMALL_BUTTON_HEIGHT, hovered ? TEXT_PRIMARY : style[2]);
		graphics.text(this.font, label, x + 3, y + 2, TEXT_PRIMARY, false);
	}

	private void drawArrow(GuiGraphicsExtractor graphics, int x, int y, boolean down, int color) {
		if (down) {
			graphics.fill(x - 2, y, x + 3, y + 1, color);
			graphics.fill(x - 1, y + 1, x + 2, y + 2, color);
			graphics.fill(x, y + 2, x + 1, y + 3, color);
		} else {
			graphics.fill(x - 1, y - 1, x, y + 4, color);
			graphics.fill(x, y, x + 1, y + 3, color);
			graphics.fill(x + 1, y + 1, x + 2, y + 2, color);
		}
	}

	private void drawDashedOutline(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
		for (int i = 0; i < w; i += 4) {
			int end = Math.min(x + i + 2, x + w);
			graphics.fill(x + i, y, end, y + 1, color);
			graphics.fill(x + i, y + h - 1, end, y + h, color);
		}
		for (int i = Math.max(0, (this.listTop - y) / 4 * 4); i < Math.min(h, this.listBottom - y + 1); i += 4) {
			int end = Math.min(y + i + 2, y + h);
			graphics.fill(x, y + i, x + 1, end, color);
			graphics.fill(x + w - 1, y + i, x + w, end, color);
		}
	}

	private void handleSidebarClick(double mouseX, double mouseY, int button, boolean shiftDown, boolean doubleClick) {
		ClientTacticsDataStore.WorldContext worldContext = this.worldContext();
		if (worldContext == null || !this.inList(mouseY)) {
			return;
		}
		for (int i = 0; i < this.rowHitCount; i++) {
			RowHit row = this.rowHits.get(i);
			if (!row.containsHeader(mouseX, mouseY)) {
				continue;
			}
			if (row.containsFollow(mouseX, mouseY)) {
				if (button == 0) {
					this.orderGroupRow = row.row;
					this.orderGroup(row.row, true);
				}
				return;
			}
			if (row.containsStay(mouseX, mouseY)) {
				if (button == 0) {
					this.orderGroup(row.row, false);
				}
				return;
			}
			if (row.containsStripe(mouseX, mouseY)) {
				RegionColor current = this.dataStore.getGroupColor(worldContext, row.row);
				RegionColor next;
				if (button == 1) {
					next = RegionColor.NONE;
				} else if (current == RegionColor.NONE) {
					next = shiftDown ? RegionColor.WHITE : RegionColor.BLUE;
				} else {
					next = shiftDown ? current.previousPaletteColor() : current.nextPaletteColor();
				}
				this.dataStore.setGroupColor(worldContext, row.row, next);
				return;
			}
			if ((button == 1 && shiftDown) || (button == 0 && doubleClick)) {
				if (doubleClick) {
					// The first click of the double-click already toggled; undo it so renaming keeps the layout.
					this.collapsedRows.flip(row.row);
				}
				this.startGroupRename(row.row, this.groupNames.get(row.row));
				return;
			}
			if (button == 0) {
				this.collapsedRows.flip(row.row);
			}
			return;
		}

		CardHit card = this.findCardHit(mouseX, mouseY);
		if (card == null) {
			return;
		}
		if (button == 0) {
			this.dragHandler.arm(card.guard, card.group, card.group < 0, mouseX, mouseY);
		} else if (button == 1) {
			this.guardMenuGuard = card.guard;
			this.guardMenuGroup = card.group;
			this.guardMenuX = (int) mouseX;
			this.guardMenuY = (int) mouseY;
		}
	}

	private void addGroup(ClientTacticsDataStore.WorldContext worldContext) {
		if (worldContext == null || this.dataStore.groupCount(worldContext) >= ClientTacticsDataStore.MAX_GROUPS) {
			return;
		}
		int nextRow = this.dataStore.groupCount(worldContext);
		if (this.sendCommand("guards groups add")) {
			this.dataStore.expectGroup(worldContext, nextRow);
			this.localRevision++;
			this.setStatus(Component.translatable(KEY + "status.group_added", nextRow + 1), STATUS_SUCCESS);
		}
	}

	private List<Component> groupLimitTooltip() {
		if (this.groupLimitTooltipCache == null) {
			this.groupLimitTooltipCache = List.of(Component.translatable(KEY + "add_group.limit", ClientTacticsDataStore.MAX_GROUPS));
		}
		return this.groupLimitTooltipCache;
	}

	// ---------------------------------------------------------------- tooltips

	private void renderTooltips(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY, boolean popupOpen) {
		if (popupOpen) {
			return;
		}
		for (UiButton button : this.buttons) {
			if (button.contains(mouseX, mouseY) && !button.tooltip.isEmpty()) {
				// Vanilla shifts tooltips up 12px without clamping the top; toolbar buttons sit above that margin.
				graphics.setComponentTooltipForNextFrame(this.font, button.tooltip, mouseX, Math.max(16, mouseY));
				return;
			}
		}
		RegionColor swatch = this.barSwatchAt(mouseX, mouseY);
		if (swatch != null) {
			this.renderSwatchTooltip(graphics, worldContext, swatch, mouseX, mouseY);
			return;
		}
		if (worldContext == null) {
			return;
		}
		if (this.contains(this.sideX, this.sideY, this.sideW, this.sideH, mouseX, mouseY)) {
			this.renderSidebarTooltip(graphics, worldContext, mouseX, mouseY);
			return;
		}
		this.renderChunkTooltip(graphics, worldContext, mouseX, mouseY);
	}

	private void renderSwatchTooltip(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, RegionColor swatch, int mouseX, int mouseY) {
		int row = this.rowForColor(worldContext, swatch);
		if (this.tooltipSwatch != swatch || this.swatchTooltip.isEmpty()) {
			this.tooltipSwatch = swatch;
			List<Component> lines = new ArrayList<>(3);
			lines.add(Component.translatable(KEY + "palette.tooltip", swatch.labelText(), swatch.hotkeyLabel()));
			lines.add(row >= 0
				? Component.translatable(KEY + "palette.tooltip.group", this.groupNames.get(row)).withStyle(ChatFormatting.GRAY)
				: tr("palette.tooltip.unbound").withStyle(ChatFormatting.DARK_GRAY));
			lines.add(tr("palette.tooltip.body").withStyle(ChatFormatting.GRAY));
			this.swatchTooltip = lines;
		}
		graphics.setComponentTooltipForNextFrame(this.font, this.swatchTooltip, mouseX, mouseY);
	}

	private void renderSidebarTooltip(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		if (!this.inList(mouseY)) {
			return;
		}
		for (int i = 0; i < this.rowHitCount; i++) {
			RowHit hit = this.rowHits.get(i);
			if (!hit.containsHeader(mouseX, mouseY) || this.editingRow == hit.row) {
				continue;
			}
			int part = hit.containsFollow(mouseX, mouseY) ? 1 : hit.containsStay(mouseX, mouseY) ? 2 : hit.containsStripe(mouseX, mouseY) ? 3 : 0;
			RegionColor color = this.dataStore.getGroupColor(worldContext, hit.row);
			if (hit.row != this.tooltipRow || part != this.tooltipRowPart || color != this.tooltipRowColor) {
				this.tooltipRow = hit.row;
				this.tooltipRowPart = part;
				this.tooltipRowColor = color;
				String name = this.groupNames.get(hit.row);
				this.rowTooltip = switch (part) {
					case 1 -> List.of(Component.translatable(KEY + "group.follow.tooltip", name));
					case 2 -> List.of(Component.translatable(KEY + "group.stay.tooltip", name));
					case 3 -> List.of(
						color == RegionColor.NONE ? tr("group.swatch.none") : Component.translatable(KEY + "group.swatch", color.labelText()),
						tr("group.swatch.body").withStyle(ChatFormatting.GRAY),
						tr("group.swatch.link").withStyle(ChatFormatting.DARK_GRAY)
					);
					default -> List.of(Component.literal(name), tr("group.name.tooltip").withStyle(ChatFormatting.GRAY));
				};
			}
			graphics.setComponentTooltipForNextFrame(this.font, this.rowTooltip, mouseX, mouseY);
			return;
		}
		CardHit card = this.findCardHit(mouseX, mouseY);
		if (card != null) {
			if (card.containsBadge(mouseX, mouseY)) {
				graphics.setComponentTooltipForNextFrame(this.font, card.guard.rank() == 2 ? TOOLTIP_RANK_COMMANDER : TOOLTIP_RANK_CAPTAIN, mouseX, mouseY);
				return;
			}
			this.renderGuardTooltip(graphics, card.guard, card.group, mouseX, mouseY);
		}
	}

	private void renderGuardTooltip(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int group, int mouseX, int mouseY) {
		if (guard != this.tooltipGuard || group != this.tooltipGuardGroup) {
			this.tooltipGuard = guard;
			this.tooltipGuardGroup = group;
			this.guardTooltip = this.buildGuardTooltip(guard, group);
		}
		graphics.setComponentTooltipForNextFrame(this.font, this.guardTooltip, mouseX, mouseY);
	}

	private List<Component> buildGuardTooltip(ClientGuardRosterStore.GuardRosterEntry guard, int group) {
		List<Component> tooltip = new ArrayList<>(10);
		tooltip.add(Component.literal(guard.displayName()));
		if (guard.rank() == 2) {
			tooltip.add(RANK_COMMANDER.copy().withStyle(ChatFormatting.GOLD));
		} else if (guard.rank() == 1) {
			tooltip.add(RANK_CAPTAIN.copy().withStyle(ChatFormatting.GRAY));
		}
		String groupName = group < 0 ? UNASSIGNED.getString() : (group < this.groupNames.size() ? this.groupNames.get(group) : guard.groupName());
		boolean pending = this.pendingAssignments.containsKey(guard.guardUuid());
		if (!guard.loaded()) {
			tooltip.add(tr("guard.not_loaded").withStyle(ChatFormatting.GRAY));
			tooltip.add(Component.translatable(KEY + (pending ? "guard.group_pending" : "guard.group"), groupName)
				.withStyle(pending ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
			tooltip.add(GUARD_DRAG_HINT);
			tooltip.add(GUARD_MENU_HINT);
			return tooltip;
		}
		if (!guard.role().isEmpty()) {
			tooltip.add(enumText("role", guard.role()).withStyle(ChatFormatting.AQUA));
		}
		tooltip.add(Component.translatable(KEY + "guard.orders", tr("order." + orderKey(guard))).withStyle(ChatFormatting.GRAY));
		if (!guard.behavior().isEmpty()) {
			tooltip.add(Component.translatable(KEY + "guard.behavior", enumText("behavior", guard.behavior())).withStyle(ChatFormatting.GRAY));
		}
		if (guard.hasHome()) {
			tooltip.add(tr("guard.has_home").withStyle(ChatFormatting.GRAY));
		}
		if (guard.maxHealth() > 0.0F) {
			tooltip.add(Component.translatable(KEY + "guard.health", Mth.ceil(guard.health()), Mth.ceil(guard.maxHealth())).withStyle(ChatFormatting.GRAY));
		}
		if (guard.experience() >= 0) {
			tooltip.add(guard.level() >= 10
				? Component.translatable(KEY + "guard.level_max", guard.level()).withStyle(ChatFormatting.GRAY)
				: Component.translatable(KEY + "guard.level", guard.level(), guard.experience(), guard.level() * 120).withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(Component.literal(levelText(guard.level())).withStyle(ChatFormatting.GRAY));
		}
		ItemStack weapon = guard.mainHand();
		Component weaponName = weapon == null || weapon.isEmpty() ? tr("guard.none") : weapon.getHoverName();
		tooltip.add(Component.translatable(KEY + "guard.weapon", weaponName, weaponDamage(weapon)).withStyle(ChatFormatting.GRAY));
		int totalArmor = armorPoints(guard.helmet()) + armorPoints(guard.chest()) + armorPoints(guard.legs()) + armorPoints(guard.boots());
		tooltip.add(Component.translatable(KEY + "guard.armor", totalArmor).withStyle(ChatFormatting.GRAY));
		tooltip.add(guard.distance() >= 0.0D
			? Component.translatable(KEY + "guard.distance", String.format(Locale.ROOT, "%.1f", guard.distance())).withStyle(ChatFormatting.GRAY)
			: tr("guard.distance_unknown").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable(KEY + (pending ? "guard.group_pending" : "guard.group"), groupName)
			.withStyle(pending ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
		tooltip.add(GUARD_DRAG_HINT);
		tooltip.add(GUARD_MENU_HINT);
		return tooltip;
	}

	// ---------------------------------------------------------------- help

	private boolean helpFitsTwoColumns() {
		return this.helpColumnWidth(HELP_MAP) + this.helpColumnWidth(HELP_GROUPS) + 30 <= this.panelWidth - 16;
	}

	private int helpColumnWidth(Component[][] rows) {
		int keyWidth = 0;
		int descWidth = 0;
		for (Component[] row : rows) {
			keyWidth = Math.max(keyWidth, this.font.width(row[0]));
			descWidth = Math.max(descWidth, this.font.width(row[1]));
		}
		return keyWidth + descWidth + 12;
	}

	private void renderHelp(GuiGraphicsExtractor graphics) {
		int areaX = this.panelX + 4;
		int areaY = this.panelY + HEADER_HEIGHT;
		int areaW = this.panelWidth - 8;
		int areaH = this.panelHeight - HEADER_HEIGHT - 4;
		graphics.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0x99000000);

		boolean twoColumns = this.helpFitsTwoColumns();
		int lineHeight = 10;
		int rows = twoColumns ? Math.max(HELP_MAP.length, HELP_GROUPS.length) : (this.helpPage == 0 ? HELP_MAP.length : HELP_GROUPS.length);
		int maxRows = Math.max(1, (areaH - 56) / lineHeight);
		int visibleRows = Math.min(rows, maxRows);
		int width = twoColumns
			? Math.min(areaW - 8, this.helpColumnWidth(HELP_MAP) + this.helpColumnWidth(HELP_GROUPS) + 30)
			: Math.min(areaW - 8, this.helpColumnWidth(this.helpPage == 0 ? HELP_MAP : HELP_GROUPS) + 16);
		int height = 22 + visibleRows * lineHeight + 26;
		int x = areaX + (areaW - width) / 2;
		int y = areaY + Math.max(2, (areaH - height) / 2);
		graphics.fill(x, y, x + width, y + height, 0xF60F151D);
		graphics.outline(x, y, width, height, ACCENT_ZONES);

		Component note;
		if (twoColumns) {
			int columnW = (width - 24) / 2;
			this.renderHelpColumn(graphics, HELP_TITLE_MAP, HELP_MAP, x + 8, y + 7, columnW, visibleRows, ACCENT_ZONES);
			this.renderHelpColumn(graphics, HELP_TITLE_GROUPS, HELP_GROUPS, x + 16 + columnW, y + 7, columnW, visibleRows, ACCENT_GROUPS);
			note = HELP_NOTE_GROUPS;
			graphics.text(this.font, ellipsize(this.font, HELP_NOTE_MAP.getString(), width - 16), x + 8, y + height - 32, TEXT_MUTED, false);
		} else {
			boolean map = this.helpPage == 0;
			this.renderHelpColumn(graphics, map ? HELP_TITLE_MAP : HELP_TITLE_GROUPS, map ? HELP_MAP : HELP_GROUPS, x + 8, y + 7, width - 16, visibleRows, map ? ACCENT_ZONES : ACCENT_GROUPS);
			note = map ? HELP_NOTE_MAP : HELP_NOTE_GROUPS;
		}
		graphics.text(this.font, ellipsize(this.font, note.getString(), width - 16), x + 8, y + height - 22, TEXT_MUTED, false);
		Component footer = !twoColumns && this.helpPage == 0 ? HELP_NEXT : HELP_DISMISS;
		graphics.text(this.font, ellipsize(this.font, footer.getString(), width - 16), x + 8, y + height - 12, KEY_COLOR, false);
	}

	// The legend is a rare overlay, not a hot path, so its strings are trimmed per frame.
	private void renderHelpColumn(GuiGraphicsExtractor graphics, Component title, Component[][] rows, int x, int y, int width, int visibleRows, int accent) {
		graphics.text(this.font, title, x, y, accent, false);
		int keyWidth = 0;
		for (Component[] row : rows) {
			keyWidth = Math.max(keyWidth, this.font.width(row[0]));
		}
		keyWidth = Math.min(keyWidth, width / 2);
		int rowY = y + 15;
		for (int i = 0; i < Math.min(visibleRows, rows.length); i++) {
			graphics.text(this.font, ellipsize(this.font, rows[i][0].getString(), keyWidth), x, rowY, KEY_COLOR, false);
			graphics.text(this.font, ellipsize(this.font, rows[i][1].getString(), width - keyWidth - 8), x + keyWidth + 8, rowY, TEXT_SECONDARY, false);
			rowY += 10;
		}
	}

	// ---------------------------------------------------------------- status

	private void setStatus(Component text, int color) {
		this.statusText = text;
		this.statusColor = color;
		this.statusUntil = System.currentTimeMillis() + STATUS_DURATION_MILLIS;
	}

	private boolean hasStatus() {
		if (this.statusText != null && System.currentTimeMillis() > this.statusUntil) {
			this.statusText = null;
		}
		return this.statusText != null;
	}

	// ---------------------------------------------------------------- drag and drop

	private void updateDragTarget(int mouseX, int mouseY) {
		if (!this.dragHandler.isActive()) {
			this.dragTarget = null;
			return;
		}
		DropHit target = this.findDropHit(mouseX, mouseY);
		this.dragTarget = target == null ? null : target.copy(this.dragTargetScratch);
		ClientGuardRosterStore.GuardRosterEntry guard = this.dragHandler.draggedGuard();
		int currentGroup = guard == null ? -1 : this.displayGroup(guard);
		Object labelKey;
		if (this.dragTarget == null) {
			labelKey = DRAG_CANCEL;
		} else if (this.dragTarget.kind == DropKind.UNASSIGN) {
			labelKey = currentGroup < 0 ? "same:-1" : DRAG_UNASSIGN;
		} else if (this.dragTarget.kind == DropKind.NEW_GROUP) {
			labelKey = DRAG_NEW;
		} else {
			labelKey = (this.dragTarget.group == currentGroup ? "same:" : "move:") + this.dragTarget.group;
		}
		if (!labelKey.equals(this.dragLabelKey)) {
			this.dragLabelKey = labelKey;
			if (labelKey instanceof Component component) {
				this.dragLabel = component;
				this.dragAccent = this.dragTarget == null ? TEXT_MUTED : DROP_HOVER_BORDER;
			} else {
				boolean same = ((String) labelKey).startsWith("same:");
				String name = this.dragTarget.kind == DropKind.UNASSIGN ? UNASSIGNED.getString() : this.groupNames.get(this.dragTarget.group);
				this.dragLabel = Component.translatable(KEY + (same ? "drag.same" : "drag.move"), name);
				this.dragAccent = same ? TEXT_MUTED : DROP_HOVER_BORDER;
			}
		}
	}

	private void autoScrollWhileDragging(int mouseX, int mouseY) {
		if (!this.dragHandler.isActive() || mouseX < this.sideX || mouseX >= this.sideX + this.sideW) {
			return;
		}
		int delta = 0;
		if (mouseY >= this.listTop - 4 && mouseY < this.listTop + DRAG_AUTOSCROLL_EDGE) {
			delta = -DRAG_AUTOSCROLL_SPEED;
		} else if (mouseY > this.listBottom - DRAG_AUTOSCROLL_EDGE && mouseY <= this.listBottom + 4) {
			delta = DRAG_AUTOSCROLL_SPEED;
		}
		if (delta != 0) {
			this.listScrollTarget = Mth.clamp(this.listScrollTarget + delta, 0, this.listMaxScroll);
		}
	}

	private void handleDrop(double mouseX, double mouseY) {
		GuardDragHandler.DropResult result = this.dragHandler.drop();
		this.dragLabelKey = null;
		if (result.guard() == null) {
			return;
		}
		DropHit target = this.findDropHit(mouseX, mouseY);
		if (target == null) {
			return;
		}
		int targetGroup = target.kind == DropKind.UNASSIGN ? -1 : target.group;
		if (target.kind == DropKind.NEW_GROUP) {
			ClientTacticsDataStore.WorldContext worldContext = this.worldContext();
			if (worldContext == null) {
				return;
			}
			// The server appends the new group, so its row is the current count. Commands run in order,
			// so the add lands before the assignment that references it.
			targetGroup = this.dataStore.groupCount(worldContext);
			if (targetGroup >= ClientTacticsDataStore.MAX_GROUPS || !this.sendCommand("guards groups add")) {
				return;
			}
			this.dataStore.expectGroup(worldContext, targetGroup);
			this.localRevision++;
		} else if (targetGroup >= this.viewGroupCount) {
			return;
		}
		this.setPendingAssignment(result.guard(), targetGroup);
	}

	private void setPendingAssignment(ClientGuardRosterStore.GuardRosterEntry guard, int targetGroup) {
		UUID guardId = guard.guardUuid();
		// The drag snapshot can predate a sync, and an older saved move can still execute.
		int currentGroup = this.rosterStore.roster(this.worldContext()).stream()
			.filter(current -> current.guardUuid().equals(guardId)).findFirst().orElse(guard).groupIndex();
		Integer inFlight = this.awaitingConfirmation.get(guardId);
		if (targetGroup == (inFlight == null ? currentGroup : inFlight)) {
			this.pendingAssignments.remove(guardId);
		} else {
			this.pendingAssignments.put(guardId, targetGroup);
		}
		this.localRevision++;
	}

	private void revertAssignments() {
		if (this.pendingAssignments.isEmpty()) {
			return;
		}
		this.pendingAssignments.clear();
		this.localRevision++;
		this.setStatus(tr("status.reverted"), TEXT_SECONDARY);
	}

	private void saveAssignments() {
		if (this.pendingAssignments.isEmpty()) {
			return;
		}
		if (this.minecraft == null || this.minecraft.getConnection() == null) {
			this.setStatus(tr("status.no_connection"), STATUS_ERROR);
			return;
		}
		// Keep edits until the server can validate them; an older roster must never discard a dependent move.
		Map<Integer, List<UUID>> byRow = new java.util.TreeMap<>();
		Iterator<Map.Entry<UUID, Integer>> entries = this.pendingAssignments.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<UUID, Integer> entry = entries.next();
			byRow.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
		}
		this.awaitingRejected = 0;
		this.awaitingReasonKey = null;
		// Rows with a command the queue refused stay pending (re-sending an assignment is harmless), so the
		// player can save them again instead of waiting for a confirmation that can never arrive.
		Map<UUID, Integer> unsent = new HashMap<>();
		for (Map.Entry<Integer, List<UUID>> row : byRow.entrySet()) {
			boolean rowSent = true;
			for (String command : assignManyCommands(row.getKey(), row.getValue())) {
				if (this.sendCommand(command)) {
					this.awaitingBatches++;
				} else {
					rowSent = false;
				}
			}
			if (!rowSent) {
				row.getValue().forEach(guardId -> unsent.put(guardId, row.getKey()));
			}
		}
		this.pendingAssignments.keySet().removeAll(unsent.keySet());
		// The server coalesces accepted batches into roster updates; confirmation is checked against them.
		this.awaitingConfirmation.putAll(this.pendingAssignments);
		this.awaitingSentCount = this.awaitingConfirmation.size();
		this.awaitingSince = System.currentTimeMillis();
		this.awaitingCheckedRevision = this.rosterStore.revision();
		this.pendingAssignments.clear();
		this.pendingAssignments.putAll(unsent);
		this.localRevision++;
		if (unsent.isEmpty()) {
			this.setStatus(Component.translatable(KEY + "status.saving", this.awaitingSentCount), TEXT_SECONDARY);
		}
	}

	/**
	 * Builds {@code guards groups assignmany <row> <uuid> ...} commands for one target row, splitting the UUID
	 * list so every command stays within the 256-character chat limit.
	 *
	 * @param row zero-based row, or -1 to unassign (sent as row 0)
	 */
	static List<String> assignManyCommands(int row, List<UUID> guardIds) {
		String prefix = "guards groups assignmany " + (row + 1) + " ";
		List<String> commands = new ArrayList<>();
		StringBuilder builder = new StringBuilder(prefix);
		int inCommand = 0;
		for (UUID guardId : guardIds) {
			String id = guardId.toString();
			if (inCommand > 0 && (builder.length() + 1 + id.length() > MAX_COMMAND_LENGTH || inCommand >= MAX_UUIDS_PER_COMMAND)) {
				commands.add(builder.toString());
				builder.setLength(0);
				builder.append(prefix);
				inCommand = 0;
			}
			if (inCommand > 0) {
				builder.append(' ');
			}
			builder.append(id);
			inCommand++;
		}
		if (inCommand > 0) {
			commands.add(builder.toString());
		}
		return commands;
	}

	/** Applies GuardGroupResultPayloads: rejected edits roll back with the server's reason instead of a timeout guess. */
	private void processGroupResults() {
		GuardGroupResultPayload result;
		while ((result = this.rosterStore.pollGroupResult()) != null) {
			switch (result.action()) {
				case ASSIGN -> {
					if (this.awaitingBatches <= 0) {
						break;
					}
					this.awaitingBatches--;
					this.awaitingRejected += result.rejected();
					if (result.rejected() > 0 && this.awaitingReasonKey == null && !result.reasonKey().isBlank()) {
						this.awaitingReasonKey = result.reasonKey();
						this.awaitingReasonRow = result.groupRow();
					}
					if (this.awaitingBatches == 0 && this.awaitingRejected > 0) {
						// Drop the optimistic view; the roster (re-sent every second) shows what the server kept.
						int sent = this.awaitingSentCount;
						this.awaitingConfirmation.clear();
						this.localRevision++;
						this.setStatus(Component.translatable(KEY + "status.rejected_reason", this.awaitingRejected, sent,
							this.reasonText(this.awaitingReasonKey, this.awaitingReasonRow)), STATUS_ERROR);
					}
				}
				case ADD, RENAME -> {
					if (result.rejected() > 0) {
						if (result.action() == GuardGroupResultPayload.Action.ADD) {
							int rejectedRow = result.groupRow();
							if (rejectedRow >= 0) this.pendingAssignments.entrySet().removeIf(entry -> entry.getValue() == rejectedRow);
						}
						// The next roster sync restores the server's group list and names.
						this.localRevision++;
						this.setStatus(Component.translatable(KEY + (result.action() == GuardGroupResultPayload.Action.ADD ? "status.add_failed" : "status.rename_failed"),
							this.reasonText(result.reasonKey(), result.groupRow())), STATUS_ERROR);
					}
				}
			}
		}
	}

	/** Translates a server reason key; arguments are filled for the keys the group commands send. */
	private Component reasonText(String reasonKey, int row) {
		if (reasonKey == null || reasonKey.isBlank()) {
			return tr("status.reason_unknown");
		}
		if (reasonKey.endsWith(".limit")) {
			return Component.translatable(reasonKey, ClientTacticsDataStore.MAX_GROUPS);
		}
		if (reasonKey.endsWith(".invalid_uuids")) {
			return Component.translatable(reasonKey, MAX_UUIDS_PER_COMMAND);
		}
		return Component.translatable(reasonKey, row + 1, this.viewGroupCount);
	}

	private void checkAwaitingConfirmation() {
		if (this.awaitingConfirmation.isEmpty()) {
			return;
		}
		if (GuardCommandQueue.pending() > 0) {
			// Wait for the server to finish processing queued operations before starting the fallback timeout.
			this.awaitingSince = System.currentTimeMillis();
			return;
		}
		int revision = this.rosterStore.revision();
		if (revision != this.awaitingCheckedRevision) {
			this.awaitingCheckedRevision = revision;
			ClientTacticsDataStore.WorldContext context = this.worldContext();
			for (ClientGuardRosterStore.GuardRosterEntry guard : this.rosterStore.roster(context)) {
				Integer expected = this.awaitingConfirmation.get(guard.guardUuid());
				if (expected != null && expected == guard.groupIndex()) {
					this.awaitingConfirmation.remove(guard.guardUuid());
				}
			}
			if (this.awaitingConfirmation.isEmpty()) {
				this.localRevision++;
				this.setStatus(this.awaitingSentCount == 1 ? tr("status.saved.one") : Component.translatable(KEY + "status.saved", this.awaitingSentCount), STATUS_SUCCESS);
				return;
			}
		}
		if (System.currentTimeMillis() - this.awaitingSince > SAVE_CONFIRM_TIMEOUT_MILLIS) {
			// Fallback for servers without GuardGroupResultPayload: no confirming roster in time means rejected.
			this.awaitingBatches = 0;
			int rejected = this.awaitingConfirmation.size();
			this.awaitingConfirmation.clear();
			this.localRevision++;
			this.setStatus(Component.translatable(KEY + "status.rejected", rejected, this.awaitingSentCount), STATUS_ERROR);
		}
	}

	/** Effective group, or -1 when it points at a row the server does not have. */
	private int displayGroup(ClientGuardRosterStore.GuardRosterEntry guard) {
		int group = this.getEffectiveGroup(guard);
		return group < this.viewGroupCount ? group : -1;
	}

	private int getEffectiveGroup(ClientGuardRosterStore.GuardRosterEntry guard) {
		Integer pending = this.pendingAssignments.get(guard.guardUuid());
		if (pending != null) {
			return pending;
		}
		Integer awaiting = this.awaitingConfirmation.get(guard.guardUuid());
		if (awaiting != null) {
			return awaiting;
		}
		return guard.groupIndex();
	}

	// ---------------------------------------------------------------- rename

	private void startGroupRename(int row, String currentName) {
		if (this.groupRenameField == null) {
			return;
		}
		this.editingRow = row;
		this.groupRenameField.setVisible(true);
		this.groupRenameField.setValue(currentName);
		this.groupRenameField.moveCursorToEnd(false);
		this.groupRenameField.setHighlightPos(0);
		this.setFocused(this.groupRenameField);
	}

	private void commitGroupRename() {
		if (this.groupRenameField == null || this.editingRow < 0) {
			return;
		}
		ClientTacticsDataStore.WorldContext worldContext = this.worldContext();
		String requested = this.groupRenameField.getValue() == null ? "" : this.groupRenameField.getValue().trim();
		int row = this.editingRow;
		// A blank name keeps the old one instead of inventing a placeholder.
		if (worldContext == null || row >= this.viewGroupCount || requested.isEmpty() || requested.equals(this.groupNames.size() > row ? this.groupNames.get(row) : null)) {
			this.cancelGroupRename();
			return;
		}
		this.dataStore.setGroupName(worldContext, row, requested);
		this.localRevision++;
		if (this.sendCommand("guards groups rename " + (row + 1) + " " + requested)) {
			this.setStatus(Component.translatable(KEY + "status.renamed", requested), STATUS_SUCCESS);
		}
		this.cancelGroupRename();
	}

	private void cancelGroupRename() {
		if (this.groupRenameField != null) {
			this.groupRenameField.setVisible(false);
		}
		this.editingRow = -1;
		this.setFocused(null);
	}

	private boolean isInsideRenameField(double mouseX, double mouseY) {
		EditBox field = this.groupRenameField;
		return this.contains(field.getX(), field.getY(), field.getWidth(), field.getHeight(), mouseX, mouseY);
	}

	// ---------------------------------------------------------------- roster helpers

	private CardText cardText(ClientGuardRosterStore.GuardRosterEntry guard) {
		CardText text = this.cardTexts.get(guard.guardUuid());
		if (text == null) {
			text = new CardText(guard, this.font);
			this.cardTexts.put(guard.guardUuid(), text);
		}
		return text;
	}

	private String countText(int count) {
		return this.countCache.get(count);
	}

	private final CountCache countCache = new CountCache();

	private String fitGroupName(int row, int maxWidth) {
		if (row < 0 || row >= this.groupNames.size()) {
			return "";
		}
		return this.groupNameFits.fit(this.font, row, this.groupNames.get(row), maxWidth);
	}

	private final GroupNameFits groupNameFits = new GroupNameFits();

	private static int armorPoints(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (path.contains("helmet")) {
			if (path.contains("netherite") || path.contains("diamond")) return 3;
			if (path.contains("iron")) return 2;
			if (path.contains("chainmail") || path.contains("golden")) return 2;
			if (path.contains("leather")) return 1;
		}
		if (path.contains("chestplate")) {
			if (path.contains("netherite") || path.contains("diamond")) return 8;
			if (path.contains("iron")) return 6;
			if (path.contains("chainmail") || path.contains("golden")) return 5;
			if (path.contains("leather")) return 3;
		}
		if (path.contains("leggings")) {
			if (path.contains("netherite") || path.contains("diamond")) return 6;
			if (path.contains("iron")) return 5;
			if (path.contains("chainmail") || path.contains("golden")) return 3;
			if (path.contains("leather")) return 2;
		}
		if (path.contains("boots")) {
			if (path.contains("netherite") || path.contains("diamond")) return 3;
			if (path.contains("iron")) return 2;
			if (path.contains("chainmail") || path.contains("golden")) return 1;
			if (path.contains("leather")) return 1;
		}
		return 0;
	}

	private static int weaponDamage(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 1;
		}
		if (stack.is(Items.STONE_SWORD)) {
			return 5;
		}
		if (stack.is(Items.IRON_SWORD)) {
			return 6;
		}
		if (stack.is(Items.DIAMOND_SWORD)) {
			return 7;
		}
		return 1;
	}

	private static int armorGearScore(ClientGuardRosterStore.GuardRosterEntry guard) {
		return pieceScore(guard.helmet()) + pieceScore(guard.chest()) + pieceScore(guard.legs()) + pieceScore(guard.boots());
	}

	private static int pieceScore(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (path.contains("netherite")) {
			return 5;
		}
		if (path.contains("diamond")) {
			return 4;
		}
		if (path.contains("iron")) {
			return 3;
		}
		if (path.contains("chainmail") || path.contains("golden")) {
			return 2;
		}
		if (path.contains("leather")) {
			return 1;
		}
		return 0;
	}

	private String resolveGroupName(ClientTacticsDataStore.WorldContext worldContext, int row, List<ClientGuardRosterStore.GuardRosterEntry> rowGuards) {
		String stored = this.dataStore.getGroupName(worldContext, row);
		if (!rowGuards.isEmpty() && (stored == null || GENERIC_GROUP_NAME.matcher(stored.trim()).matches())) {
			String fromGuard = rowGuards.getFirst().groupName();
			if (fromGuard != null && !fromGuard.isBlank()) {
				return fromGuard;
			}
		}
		return stored == null ? "" : stored;
	}

	// ---------------------------------------------------------------- dialog

	private int dialogWidth() {
		return Math.min(DIALOG_MAX_WIDTH, this.width - 16);
	}

	private void renderUnsavedDialog(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(0, 0, this.width, this.height, 0x88000000);
		int dw = this.dialogWidth();
		int dx = (this.width - dw) / 2;
		int dy = (this.height - DIALOG_HEIGHT) / 2;
		graphics.fill(dx, dy, dx + dw, dy + DIALOG_HEIGHT, PANEL_BACKGROUND);
		graphics.outline(dx, dy, dw, DIALOG_HEIGHT, STATUS_WARNING);

		graphics.text(this.font, DIALOG_TITLE, dx + 10, dy + 9, STATUS_WARNING, false);
		Component body = Component.translatable(KEY + "dialog.body", this.pendingAssignments.size());
		graphics.text(this.font, ellipsize(this.font, body.getString(), dw - 20), dx + 10, dy + 23, TEXT_SECONDARY, false);
		graphics.text(this.font, ellipsize(this.font, DIALOG_KEYS.getString(), dw - 20), dx + 10, dy + 35, TEXT_MUTED, false);

		int buttonW = (dw - 40) / 3;
		int buttonY = dy + DIALOG_HEIGHT - DIALOG_BUTTON_HEIGHT - 10;
		this.drawDialogButton(graphics, DIALOG_SAVE, dx + 10, buttonY, buttonW, mouseX, mouseY, STYLE_ON);
		this.drawDialogButton(graphics, DIALOG_DISCARD, dx + 20 + buttonW, buttonY, buttonW, mouseX, mouseY, new int[] {0xFF3A1A1A, 0xFF5A2A2A, 0xFFAA4444});
		this.drawDialogButton(graphics, DIALOG_CANCEL, dx + 30 + buttonW * 2, buttonY, buttonW, mouseX, mouseY, STYLE_DARK);
	}

	private void drawDialogButton(GuiGraphicsExtractor graphics, Component label, int x, int y, int w, int mouseX, int mouseY, int[] style) {
		boolean hovered = this.contains(x, y, w, DIALOG_BUTTON_HEIGHT, mouseX, mouseY);
		graphics.fill(x, y, x + w, y + DIALOG_BUTTON_HEIGHT, hovered ? style[1] : style[0]);
		graphics.outline(x, y, w, DIALOG_BUTTON_HEIGHT, hovered ? TEXT_PRIMARY : style[2]);
		String text = ellipsize(this.font, label.getString(), w - 6);
		graphics.text(this.font, text, x + (w - this.font.width(text)) / 2, y + 4, TEXT_PRIMARY, false);
	}

	private boolean handleDialogClick(double mouseX, double mouseY) {
		int dw = this.dialogWidth();
		int dx = (this.width - dw) / 2;
		int dy = (this.height - DIALOG_HEIGHT) / 2;
		int buttonW = (dw - 40) / 3;
		int buttonY = dy + DIALOG_HEIGHT - DIALOG_BUTTON_HEIGHT - 10;
		if (this.contains(dx + 10, buttonY, buttonW, DIALOG_BUTTON_HEIGHT, mouseX, mouseY)) {
			this.dialogSave();
		} else if (this.contains(dx + 20 + buttonW, buttonY, buttonW, DIALOG_BUTTON_HEIGHT, mouseX, mouseY)) {
			this.pendingAssignments.clear();
			this.localRevision++;
			this.runPendingAction();
		} else if (this.contains(dx + 30 + buttonW * 2, buttonY, buttonW, DIALOG_BUTTON_HEIGHT, mouseX, mouseY)) {
			this.closeDialog();
		}
		return true;
	}

	private void dialogSave() {
		this.saveAssignments();
		this.runPendingAction();
	}

	private void closeDialog() {
		this.showUnsavedDialog = false;
		this.pendingAction = null;
	}

	private void runPendingAction() {
		this.showUnsavedDialog = false;
		Runnable action = this.pendingAction;
		this.pendingAction = null;
		if (action != null) {
			action.run();
		}
	}

	@Override
	public void onClose() {
		if (!this.pendingAssignments.isEmpty() && !this.showUnsavedDialog) {
			this.dragHandler.cancel();
			this.paletteOpen = false;
			this.followMenuOpen = false;
			this.guardMenuGuard = null;
			this.pendingAction = this::onClose;
			this.showUnsavedDialog = true;
			return;
		}
		if (this.showUnsavedDialog) {
			// Re-entry from the dialog's Save/Discard: assignments were saved or discarded already.
			this.showUnsavedDialog = false;
		}
		super.onClose();
	}

	// imageWidth/imageHeight are final in 26.1; the screen is full-size, so no click is "outside" it
	// (the 1.21 code got the same effect by setting imageWidth/imageHeight to the screen size).
	@Override
	protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
		return false;
	}

	@Override
	public void removed() {
		super.removed();
		// Terrain textures are only needed while the map is open; colors stay cached for a fast reopen.
		GuardVillagersClient.terrainCache().releaseTextures();
	}

	// ---------------------------------------------------------------- helpers

	private boolean sendCommand(String command) {
		switch (GuardCommandQueue.enqueue(command)) {
			case QUEUED -> {
				return true;
			}
			case QUEUE_FULL -> this.setStatus(tr("status.queue_full"), STATUS_ERROR);
			case NO_CONNECTION -> this.setStatus(tr("status.no_connection"), STATUS_ERROR);
		}
		return false;
	}

	private ClientTacticsDataStore.WorldContext worldContext() {
		if (this.minecraft == null || this.minecraft.level == null) {
			this.contextLevel = null;
			this.cachedContext = null;
			return null;
		}
		if (this.minecraft.level != this.contextLevel || this.cachedContext == null) {
			this.contextLevel = this.minecraft.level;
			this.cachedContext = ClientTacticsDataStore.resolveContext(this.minecraft, this.minecraft.level);
			this.dimensionName = prettyDimension(this.minecraft.level.dimension().identifier().getPath());
		}
		return this.cachedContext;
	}

	// "the_nether" -> "The Nether"; modded dimensions read fine without a lang entry.
	private static String prettyDimension(String path) {
		StringBuilder builder = new StringBuilder(path.length());
		boolean upper = true;
		for (char c : path.toCharArray()) {
			if (c == '_' || c == '/') {
				builder.append(' ');
				upper = true;
			} else {
				builder.append(upper ? Character.toUpperCase(c) : c);
				upper = false;
			}
		}
		return builder.toString();
	}

	private void renderCenteredMessage(GuiGraphicsExtractor graphics, Component title, Component body, int x, int y, int w, int h) {
		int lines = body == null ? 1 : 2;
		int textY = y + (h - lines * 11) / 2;
		String titleText = ellipsize(this.font, title.getString(), w - 8);
		graphics.text(this.font, titleText, x + (w - this.font.width(titleText)) / 2, textY, TEXT_PRIMARY, true);
		if (body != null) {
			String bodyText = ellipsize(this.font, body.getString(), w - 8);
			graphics.text(this.font, bodyText, x + (w - this.font.width(bodyText)) / 2, textY + 11, TEXT_SECONDARY, false);
		}
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int scroll, int maxScroll, int totalContent) {
		graphics.fill(x, y, x + width, y + height, SCROLLBAR_TRACK_COLOR);
		if (maxScroll <= 0 || totalContent <= 0) {
			return;
		}
		int thumbH = Math.max(10, height * height / totalContent);
		int thumbY = y + (int) ((long) scroll * (height - thumbH) / maxScroll);
		graphics.fill(x, thumbY, x + width, thumbY + thumbH, 0xFF5A6E86);
	}

	private void drawButton(GuiGraphicsExtractor graphics, UiButton button, int mouseX, int mouseY, int[] style) {
		if (!button.visible || button.label == null) {
			return;
		}
		boolean hovered = button.enabled && button.contains(mouseX, mouseY);
		graphics.fill(button.x, button.y, button.x + button.w, button.y + button.h, button.enabled ? (hovered ? style[1] : style[0]) : DISABLED_FILL);
		graphics.outline(button.x, button.y, button.w, button.h, button.enabled ? (hovered ? TEXT_PRIMARY : style[2]) : DISABLED_BORDER);
		graphics.text(
			this.font,
			button.label,
			button.x + button.w - button.labelWidth - (button.w - button.labelWidth) / 2,
			button.y + (button.h - 8) / 2,
			button.enabled ? TEXT_PRIMARY : TEXT_MUTED,
			false
		);
	}

	private static double lerpPaneScroll(double current, int target) {
		if (Math.abs(target - current) <= 0.5D) {
			return target;
		}
		return current + ((target - current) * PANE_SCROLL_LERP_FACTOR);
	}

	private boolean inList(double mouseY) {
		return mouseY >= this.listTop && mouseY < this.listBottom;
	}

	private boolean contains(int x, int y, int width, int height, double mouseX, double mouseY) {
		return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
	}

	private void computeLayout() {
		// Tighter margins on tiny GUIs (e.g. 320x240 scaled) so the map and sidebar keep usable space.
		int margin = this.width < 420 || this.height < 280 ? 2 : 6;
		this.panelX = margin;
		this.panelY = margin;
		this.panelWidth = this.width - margin * 2;
		this.panelHeight = this.height - margin * 2;
		int innerX = this.panelX + 4;
		int innerW = this.panelWidth - 8;
		int bodyTop = this.panelY + HEADER_HEIGHT + 2;

		this.barX = innerX;
		this.barW = innerW;
		this.barY = this.panelY + this.panelHeight - 3 - BAR_HEIGHT;
		int bodyBottom = this.barY - 3;

		this.sideW = Mth.clamp(innerW * 38 / 100, SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH);
		if (innerW - this.sideW - 4 < MAP_MIN_WIDTH) {
			this.sideW = Math.max(80, innerW - 4 - MAP_MIN_WIDTH);
		}
		this.mapX = innerX + 1;
		this.mapY = bodyTop + 1;
		this.mapW = innerW - this.sideW - 6;
		this.mapH = bodyBottom - bodyTop - 2;
		this.sideX = innerX + innerW - this.sideW;
		this.sideY = bodyTop;
		this.sideH = bodyBottom - bodyTop;

		this.layoutHeaderButtons();
		this.updateFollowGroupLabel();
		// One control row (zone tools left, orders right) when it fits; otherwise orders move to the second row.
		int zoneWidth = this.font.width(ZONE_LABEL) + 5 + 4 * SWATCH_STRIDE
			+ this.paletteMoreButton.w + this.eraseButton.w + this.clearButton.w + 9;
		this.barWide = zoneWidth + 16 + this.ordersWidth(true) <= this.barW - 8;
	}

	private void resetHits() {
		this.cardHitCount = 0;
		this.rowHitCount = 0;
		this.dropHitCount = 0;
	}

	// Hitboxes are clipped to the scrolling list so rows scrolled out of view cannot be grabbed.
	private void addCardHit(ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int w, int h, int group) {
		int top = Math.max(y, this.listTop);
		int bottom = Math.min(y + h, this.listBottom);
		if (bottom <= top) {
			this.drawnBadgeX = Integer.MIN_VALUE;
			return;
		}
		if (this.cardHitCount == this.cardHits.size()) {
			this.cardHits.add(new CardHit());
		}
		CardHit hit = this.cardHits.get(this.cardHitCount++);
		hit.badgeX = this.drawnBadgeX;
		hit.badgeY = this.drawnBadgeY;
		this.drawnBadgeX = Integer.MIN_VALUE;
		hit.guard = guard;
		hit.x = x;
		hit.y = top;
		hit.w = w;
		hit.h = bottom - top;
		hit.group = group;
	}

	private void addRowHit(int row, int x, int y, int w, int h, int followX, int followW, int stayX, int stayW) {
		int top = Math.max(y, this.listTop);
		int bottom = Math.min(y + h, this.listBottom);
		if (bottom <= top) {
			return;
		}
		if (this.rowHitCount == this.rowHits.size()) {
			this.rowHits.add(new RowHit());
		}
		RowHit hit = this.rowHits.get(this.rowHitCount++);
		hit.row = row;
		hit.x = x;
		hit.y = top;
		hit.w = w;
		hit.h = bottom - top;
		hit.followX = followX;
		hit.followW = followW;
		hit.stayX = stayX;
		hit.stayW = stayW;
	}

	private void addDropHit(DropKind kind, int group, int x, int y, int w, int h) {
		int top = Math.max(y, this.listTop);
		int bottom = Math.min(y + h, this.listBottom);
		if (bottom <= top) {
			return;
		}
		if (this.dropHitCount == this.dropHits.size()) {
			this.dropHits.add(new DropHit());
		}
		DropHit hit = this.dropHits.get(this.dropHitCount++);
		hit.kind = kind;
		hit.group = group;
		hit.x = x;
		hit.y = top;
		hit.w = w;
		hit.h = bottom - top;
	}

	private CardHit findCardHit(double mouseX, double mouseY) {
		for (int i = 0; i < this.cardHitCount; i++) {
			CardHit hit = this.cardHits.get(i);
			if (mouseX >= hit.x && mouseY >= hit.y && mouseX < hit.x + hit.w && mouseY < hit.y + hit.h) {
				return hit;
			}
		}
		return null;
	}

	private DropHit findDropHit(double mouseX, double mouseY) {
		for (int i = 0; i < this.dropHitCount; i++) {
			DropHit hit = this.dropHits.get(i);
			if (mouseX >= hit.x && mouseY >= hit.y && mouseX < hit.x + hit.w && mouseY < hit.y + hit.h) {
				return hit;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- small types

	private enum DropKind {
		GROUP,
		NEW_GROUP,
		UNASSIGN
	}

	private enum SortMode {
		GEAR("gear", Comparator.comparingInt(GuardTacticsScreen::armorGearScore).reversed()
			.thenComparing(Comparator.comparingInt(ClientGuardRosterStore.GuardRosterEntry::level).reversed())),
		LEVEL("level", Comparator.comparingInt(ClientGuardRosterStore.GuardRosterEntry::level).reversed()
			.thenComparing(Comparator.comparingInt(GuardTacticsScreen::armorGearScore).reversed())),
		HEALTH("health", Comparator.comparingDouble(SortMode::healthRatio)),
		DISTANCE("distance", Comparator.comparingDouble(SortMode::sortableDistance)),
		NAME("name", (a, b) -> 0);

		private final Component label;
		private final List<Component> tooltip;
		private final Comparator<ClientGuardRosterStore.GuardRosterEntry> comparator;

		SortMode(String id, Comparator<ClientGuardRosterStore.GuardRosterEntry> primary) {
			this.label = tr("sort." + id);
			this.tooltip = List.of(
				Component.translatable(KEY + "sort.tooltip", tr("sort." + id)),
				tr("sort.tooltip.body").withStyle(ChatFormatting.GRAY)
			);
			// Name, then UUID, as tie-breakers keep the order stable between roster syncs.
			this.comparator = primary
				.thenComparing(ClientGuardRosterStore.GuardRosterEntry::displayName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ClientGuardRosterStore.GuardRosterEntry::guardUuid);
		}

		private SortMode next() {
			SortMode[] values = values();
			return values[(this.ordinal() + 1) % values.length];
		}

		private static double healthRatio(ClientGuardRosterStore.GuardRosterEntry guard) {
			return guard.maxHealth() > 0.0F ? guard.health() / guard.maxHealth() : 2.0D;
		}

		private static double sortableDistance(ClientGuardRosterStore.GuardRosterEntry guard) {
			return guard.distance() >= 0.0D ? guard.distance() : Double.MAX_VALUE;
		}
	}

	private static final class UiButton {
		private int x;
		private int y;
		private int w;
		private int h;
		private Component label;
		private int labelWidth;
		private boolean visible = true;
		private boolean enabled = true;
		private List<Component> tooltip = List.of();

		private void setLabel(Font font, Component label, int padding, List<Component> tooltip) {
			this.label = label;
			this.labelWidth = font.width(label);
			this.w = this.labelWidth + padding;
			this.tooltip = tooltip;
		}

		private void place(int x, int y, int h) {
			this.x = x;
			this.y = y;
			this.h = h;
		}

		private boolean contains(double mouseX, double mouseY) {
			return this.visible && this.label != null && mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.w && mouseY < this.y + this.h;
		}

		private boolean isClickable(double mouseX, double mouseY) {
			return this.enabled && this.contains(mouseX, mouseY);
		}
	}

	private static final class CardHit {
		private ClientGuardRosterStore.GuardRosterEntry guard;
		// Top-left of the rank badge, Integer.MIN_VALUE when the guard has none.
		private int badgeX;
		private int badgeY;

		private boolean containsBadge(double mouseX, double mouseY) {
			return this.badgeX != Integer.MIN_VALUE && mouseX >= this.badgeX - 1 && mouseY >= this.badgeY - 1
				&& mouseX < this.badgeX + RANK_BADGE_SIZE + 1 && mouseY < this.badgeY + RANK_BADGE_SIZE + 1;
		}
		private int x;
		private int y;
		private int w;
		private int h;
		private int group;
	}

	private static final class RowHit {
		private int row;
		private int x;
		private int y;
		private int w;
		private int h;
		private int followX;
		private int followW;
		private int stayX;
		private int stayW;

		private boolean containsHeader(double mouseX, double mouseY) {
			return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.w && mouseY < this.y + this.h;
		}

		// The colour stripe is 3px wide; the click target is widened to 6px so it is usable.
		private boolean containsStripe(double mouseX, double mouseY) {
			return this.containsHeader(mouseX, mouseY) && mouseX < this.x + 6;
		}

		private boolean containsFollow(double mouseX, double mouseY) {
			return this.containsHeader(mouseX, mouseY) && mouseX >= this.followX && mouseX < this.followX + this.followW;
		}

		private boolean containsStay(double mouseX, double mouseY) {
			return this.containsHeader(mouseX, mouseY) && mouseX >= this.stayX && mouseX < this.stayX + this.stayW;
		}
	}

	private static final class DropHit {
		private DropKind kind;
		private int group;
		private int x;
		private int y;
		private int w;
		private int h;

		private DropHit copy(DropHit into) {
			into.kind = this.kind;
			into.group = this.group;
			into.x = this.x;
			into.y = this.y;
			into.w = this.w;
			into.h = this.h;
			return into;
		}
	}

	/** Remembers the last fitted (ellipsized) string for a component/width pair. */
	private static final class FitText {
		private Component source;
		private int width = Integer.MIN_VALUE;
		private String value = "";

		private String fit(Font font, Component component, int maxWidth) {
			if (component != this.source || maxWidth != this.width) {
				this.source = component;
				this.width = maxWidth;
				this.value = ellipsize(font, component.getString(), maxWidth);
			}
			return this.value;
		}
	}

	/** Per-guard display strings, rebuilt when a new roster arrives. */
	private static final class CardText {
		private final String name;
		private final int nameWidth;
		private final String distance;
		private final int distanceWidth;
		private final String levelNumber;
		private int miniWidth = Integer.MIN_VALUE;
		private String miniName = "";
		private int rowWidth = Integer.MIN_VALUE;
		private String rowName = "";

		private CardText(ClientGuardRosterStore.GuardRosterEntry guard, Font font) {
			this.name = guard.displayName();
			this.nameWidth = font.width(this.name);
			this.distance = guard.distance() >= 0.0D
				? Component.translatable(KEY + "distance", (int) Math.round(guard.distance())).getString()
				: "";
			this.distanceWidth = font.width(this.distance);
			this.levelNumber = Integer.toString(guard.level());
		}

		private String miniName(Font font, int width) {
			if (width != this.miniWidth) {
				this.miniWidth = width;
				this.miniName = fitName(font, this.name, width);
			}
			return this.miniName;
		}

		private String notLoaded;

		private String notLoaded(Font font) {
			if (this.notLoaded == null) {
				this.notLoaded = tr("guard.not_loaded.short").getString();
			}
			return this.notLoaded;
		}

		private String rowName(Font font, int width) {
			if (width != this.rowWidth) {
				this.rowWidth = width;
				this.rowName = fitName(font, this.name, width);
			}
			return this.rowName;
		}
	}

	/** Member-count strings by count. */
	private static final class CountCache {
		private final Int2ObjectOpenHashMap<String> values = new Int2ObjectOpenHashMap<>();

		private String get(int count) {
			String value = this.values.get(count);
			if (value == null) {
				value = Integer.toString(count);
				this.values.put(count, value);
			}
			return value;
		}
	}

	/** Fitted group names per row and width; invalidated when the source name changes. */
	private static final class GroupNameFits {
		private final Long2ObjectOpenHashMap<String> fitted = new Long2ObjectOpenHashMap<>();
		private final Int2ObjectOpenHashMap<String> sources = new Int2ObjectOpenHashMap<>();

		private String fit(Font font, int row, String name, int width) {
			String source = this.sources.get(row);
			if (!name.equals(source)) {
				this.sources.put(row, name);
				this.fitted.keySet().removeIf((long key) -> (int) (key >> 32) == row);
			}
			long key = ((long) row << 32) | (width & 0xFFFFFFFFL);
			String value = this.fitted.get(key);
			if (value == null) {
				value = ellipsize(font, name, width);
				this.fitted.put(key, value);
			}
			return value;
		}
	}
}
