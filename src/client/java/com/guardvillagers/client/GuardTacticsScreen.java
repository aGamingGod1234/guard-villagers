package com.guardvillagers.client;

import com.guardvillagers.tactics.GuardTacticsScreenHandler;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;

public final class GuardTacticsScreen extends AbstractContainerScreen<GuardTacticsScreenHandler> {
	private static final String KEY = "gui.guardvillagers.tactics.";

	private static final int PANEL_BACKGROUND = 0xF1161C25;
	private static final int PANEL_BORDER = 0xFF3B4A5E;
	private static final int TEXT_PRIMARY = 0xFFEAF1FA;
	private static final int TEXT_SECONDARY = 0xFFB6C6D6;
	private static final int TEXT_MUTED = 0xFF7D8DA0;
	private static final int ACCENT_ZONES = 0xFF8FD2FF;
	private static final int ACCENT_GROUPS = 0xFFFFCB90;
	private static final int STATUS_SUCCESS = 0xFF6FD49A;
	private static final int STATUS_ERROR = 0xFFFF7A7A;
	private static final int STATUS_WARNING = 0xFFFFD36B;
	private static final int PANE_BACKGROUND = 0xCC131B25;
	private static final int ROW_BACKGROUND = 0xCC1B2531;
	private static final int ROW_HOVER_BACKGROUND = 0xCC223041;
	private static final int ROW_BORDER = 0xFF334154;
	private static final int CARD_BACKGROUND = 0xEE17212D;
	private static final int CARD_HOVER_BACKGROUND = 0xEE213044;
	private static final int CARD_BORDER = 0xFF425067;
	private static final int CARD_HOVER_BORDER = 0xFF6C95BC;
	private static final int DROP_IDLE_BORDER = 0xFF3E5A78;
	private static final int DROP_HOVER_BORDER = 0xFF7FB2FF;
	private static final int DROP_HOVER_BACKGROUND = 0xCC253545;
	private static final int DROP_ZONE_BACKGROUND = 0x66131B25;
	private static final int DROP_ZONE_BORDER = 0xFF2A3548;
	private static final int BAR_BACKGROUND = 0xAA131B25;
	private static final int SCROLLBAR_TRACK_COLOR = 0x55394656;
	private static final int PENDING_MARKER = 0xFFFFD36B;
	private static final int BUTTON_FILL = 0xFF2A3E54;
	private static final int BUTTON_HOVER_FILL = 0xFF3B5771;
	private static final int BUTTON_BORDER = 0xFF6C95BC;
	private static final int SAVE_FILL = 0xFF1E5A3A;
	private static final int SAVE_HOVER_FILL = 0xFF2A7A4A;
	private static final int SAVE_BORDER = 0xFF3EAA6A;
	private static final int DISABLED_FILL = 0xFF222B36;
	private static final int DISABLED_BORDER = 0xFF3A4654;

	private static final int HEADER_HEIGHT = 28;
	private static final int BOTTOM_BAR_HEIGHT = 18;
	private static final int CONTROL_HEIGHT = 14;
	private static final int SWATCH_SIZE = 14;
	private static final int SWATCH_GAP = 4;
	private static final int PALETTE_SCROLL_ANIMATION_MILLIS = 140;
	private static final int PANE_GAP = 6;
	private static final int LEFT_PANE_RATIO = 55;
	private static final int PANE_HEADER_HEIGHT = 15;
	private static final int SCROLLBAR_SPACE = 5;
	private static final int GROUP_HEADER_HEIGHT = 20;
	private static final int GROUP_BOX_GAP = 4;
	private static final int NEW_GROUP_BOX_HEIGHT = 26;
	private static final int EMPTY_GROUPS_BOX_HEIGHT = 40;
	private static final int MINI_CARD_WIDTH = 78;
	private static final int MINI_CARD_HEIGHT = 38;
	private static final int MINI_CARD_GAP = 4;
	private static final int ROSTER_CARD_HEIGHT = 26;
	private static final int ROSTER_CARD_GAP = 2;
	private static final int UNASSIGN_DROP_HEIGHT = 18;
	private static final int WHEEL_SCROLL_PIXELS = 24;
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
	private static final Pattern GENERIC_GROUP_NAME = Pattern.compile("Role(\\s+\\d+)?");

	private static final ItemStack GUARD_HEAD_ICON = new ItemStack(Items.PLAYER_HEAD);

	private static final Component TAB_ZONES = tr("tab.zones");
	private static final Component TAB_GROUPS = tr("tab.groups");
	private static final Component SUBTITLE_ZONES = tr("subtitle.zones");
	private static final Component SUBTITLE_GROUPS = tr("subtitle.groups");
	private static final Component HELP_LABEL = Component.literal("?");
	private static final Component GROUPS_HEADER = tr("groups.header");
	private static final Component UNASSIGN_TARGET = tr("guards.unassign_target");
	private static final Component GROUP_EMPTY = tr("group.empty");
	private static final Component GROUP_NEW = tr("group.new");
	private static final Component GROUPS_NONE_TITLE = tr("group.none.title");
	private static final Component GROUPS_NONE_BODY = tr("group.none.body");
	private static final Component GUARDS_SYNCING = tr("guards.syncing");
	private static final Component GUARDS_SYNCING_BODY = tr("guards.syncing_body");
	private static final Component GUARDS_EMPTY_TITLE = tr("guards.empty.title");
	private static final Component GUARDS_EMPTY_BODY = tr("guards.empty.body");
	private static final Component GUARDS_FILTERED_EMPTY = tr("guards.filtered_empty");
	private static final Component UNASSIGNED = tr("unassigned");
	private static final Component ADD_GROUP = tr("add_group");
	private static final Component SAVED = tr("saved");
	private static final Component SAVING = tr("saving");
	private static final Component REVERT = tr("revert");
	private static final Component HINT_IDLE = tr("hint.idle");
	private static final Component HINT_MAP = tr("hint.map");
	private static final Component HINT_PAINTING = tr("hint.painting");
	private static final Component HINT_ERASING = tr("hint.erasing");
	private static final Component HINT_PANNING = tr("hint.panning");
	private static final Component HINT_GROUPS = tr("hint.groups");
	private static final Component HINT_DRAGGING = tr("hint.dragging");
	private static final Component HINT_SYNCING = tr("hint.syncing");
	private static final Component BRUSH_ERASE = tr("brush.erase");
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
	private static final Component HELP_TITLE_ZONES = tr("help.title.zones");
	private static final Component HELP_TITLE_GROUPS = tr("help.title.groups");
	private static final Component RENAME_HINT = tr("rename.hint");
	private static final Component GUARD_DRAG_HINT = tr("guard.drag_hint").withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
	private static final List<Component> TOOLTIP_TAB_ZONES = List.of(TAB_ZONES, tr("tab.zones.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY));
	private static final List<Component> TOOLTIP_TAB_GROUPS = List.of(TAB_GROUPS, tr("tab.groups.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY));
	private static final List<Component> TOOLTIP_HELP = List.of(tr("help.tooltip"));
	private static final List<Component> TOOLTIP_ADD_GROUP = List.of(tr("add_group.tooltip"));
	private static final List<Component> TOOLTIP_SAVE = List.of(tr("save.tooltip"));
	private static final List<Component> TOOLTIP_REVERT = List.of(tr("revert.tooltip"));
	private static final List<Component> TOOLTIP_FILTER_ALL = List.of(tr("filter.tooltip.all"));
	private static final List<Component> TOOLTIP_FILTER_UNASSIGNED = List.of(tr("filter.tooltip.unassigned"));
	private static final List<Component> TOOLTIP_RENAME = List.of(tr("group.name.tooltip"));
	private static final Component[][] HELP_ZONES = {
		helpRow("zones.select"), helpRow("zones.paint"), helpRow("zones.brush"), helpRow("zones.erase"),
		helpRow("zones.pan"), helpRow("zones.zoom"), helpRow("zones.color"), helpRow("zones.clear"),
		helpRow("zones.center"), helpRow("common.tab"), helpRow("common.esc")
	};
	private static final Component[][] HELP_GROUPS = {
		helpRow("groups.drag"), helpRow("groups.unassign"), helpRow("groups.new"), helpRow("groups.rename"),
		helpRow("groups.color"), helpRow("groups.clearcolor"), helpRow("groups.save"), helpRow("groups.sort"),
		helpRow("common.tab"), helpRow("common.esc")
	};
	private static final Component HELP_NOTE_ZONES = tr("help.zones.note");
	private static final Component HELP_NOTE_GROUPS = tr("help.groups.note");

	// Shown once per game session per view so new players see the controls without hunting for them.
	private static boolean zonesHelpShown;
	private static boolean groupsHelpShown;
	private static String[] levelTextCache = new String[0];

	private final ClientTacticsDataStore dataStore = ClientTacticsDataStore.getInstance();
	private final ClientGuardRosterStore rosterStore = ClientGuardRosterStore.getInstance();
	private final ChunkMapWidget chunkMapWidget = new ChunkMapWidget(this.dataStore, GuardVillagersClient.terrainCache());
	private final GuardDragHandler dragHandler = new GuardDragHandler();
	private ViewMode mode;
	private boolean helpVisible;

	// Layout
	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;
	private int contentX;
	private int contentY;
	private int contentWidth;
	private int contentHeight;
	private int barY;

	// Buttons (reused every frame)
	private final UiButton zonesTab = new UiButton();
	private final UiButton groupsTab = new UiButton();
	private final UiButton helpButton = new UiButton();
	private final UiButton addGroupButton = new UiButton();
	private final UiButton saveButton = new UiButton();
	private final UiButton revertButton = new UiButton();
	private final UiButton sortButton = new UiButton();
	private final UiButton filterButton = new UiButton();
	private final UiButton[] buttons = {
		this.zonesTab, this.groupsTab, this.helpButton, this.addGroupButton, this.saveButton, this.revertButton, this.sortButton, this.filterButton
	};

	// Palette
	private int visibleSwatches = 4;
	private int paletteScrollOffset;
	private double paletteScrollAnimationFrom;
	private double paletteScrollAnimationTo;
	private long paletteScrollAnimationStartedAt;
	private int paletteClipX;
	private int paletteClipY;
	private int paletteClipWidth;
	private int paletteClipHeight;
	private RegionColor hoveredSwatch;
	private RegionColor tooltipSwatch;
	private List<Component> swatchTooltip = List.of();

	// World context cache (resolving it builds strings, so do it once per level)
	private ClientLevel contextLevel;
	private ClientTacticsDataStore.WorldContext cachedContext;

	// Tactics view text caches
	private long tacticsHintKey = Long.MIN_VALUE;
	private Component tacticsHint = HINT_IDLE;
	private RegionColor brushLabelColor;
	private Component brushLabel = Component.empty();
	private int zoomPercentShown = -1;
	private String zoomText = "";
	private final FitText hintFit = new FitText();
	private long hoveredChunkKey = Long.MIN_VALUE;
	private long hoveredChunkSince;
	private RegionColor tooltipZoneColor;
	private String tooltipGroupName;
	private List<Component> chunkTooltip = List.of();

	// Groups view: roster grouped and sorted, rebuilt only when inputs change
	private int viewRosterRevision = -1;
	private int viewLocalRevision = -1;
	private int localRevision;
	private SortMode viewSort;
	private boolean viewFilterUnassigned;
	private ClientTacticsDataStore.WorldContext viewContext;
	private int viewGroupCount;
	private final List<List<ClientGuardRosterStore.GuardRosterEntry>> groupMembers = new ArrayList<>();
	private final List<ClientGuardRosterStore.GuardRosterEntry> rosterList = new ArrayList<>();
	private final List<String> groupNames = new ArrayList<>();
	private final Map<UUID, CardText> cardTexts = new HashMap<>();
	private int viewRosterSize;
	private boolean viewRosterLoaded;
	private Component guardsHeader = GUARDS_SYNCING;
	private SortMode sortMode = SortMode.GEAR;
	private boolean filterUnassigned;

	// Groups pane geometry and scroll
	private int leftPaneX;
	private int leftPaneY;
	private int leftPaneW;
	private int leftPaneH;
	private int rightPaneX;
	private int rightPaneY;
	private int rightPaneW;
	private int rightPaneH;
	private int leftListTop;
	private int leftListBottom;
	private int rightListTop;
	private int rightListBottom;
	private int leftPaneScrollTarget;
	private int rightPaneScrollTarget;
	private double leftPaneScrollAnimated;
	private double rightPaneScrollAnimated;
	private int leftPaneMaxScroll;
	private int rightPaneMaxScroll;

	// Hitboxes, pooled so the groups view does not allocate per frame
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
	private boolean showUnsavedDialog;
	private Runnable pendingAction;
	private int saveLabelCount = -1;
	private Component saveLabel = SAVED;
	private Component unsavedLabel = Component.empty();
	private final FitText groupsHintFit = new FitText();
	private final FitText guardsHeaderFit = new FitText();

	// Drag feedback
	private DropHit dragTarget;
	private Object dragLabelKey;
	private Component dragLabel;
	private int dragAccent = DROP_IDLE_BORDER;

	// Status line
	private Component statusText;
	private int statusColor;
	private long statusUntil;
	private final FitText statusFit = new FitText();

	// Tooltip caches for guards and group swatches
	private ClientGuardRosterStore.GuardRosterEntry tooltipGuard;
	private int tooltipGuardGroup = Integer.MIN_VALUE;
	private List<Component> guardTooltip = List.of();
	private int tooltipSwatchRow = -1;
	private RegionColor tooltipSwatchColor;
	private List<Component> groupSwatchTooltip = List.of();

	public GuardTacticsScreen(GuardTacticsScreenHandler handler, Inventory inventory, Component title) {
		super(handler, inventory, title);
		this.mode = isGroupsTitle(title) ? ViewMode.GROUPS : ViewMode.TACTICS;
	}

	private static MutableComponent tr(String suffix) {
		return Component.translatable(KEY + suffix);
	}

	private static Component[] helpRow(String id) {
		return new Component[] {tr("help." + id + ".key"), tr("help." + id + ".desc")};
	}

	private static boolean isGroupsTitle(Component title) {
		if (title.getContents() instanceof TranslatableContents content) {
			String key = content.getKey();
			return "screen.guardvillagers.groups".equals(key) || "screen.guardvillagers.hierarchy".equals(key);
		}
		String normalized = title.getString().toLowerCase(Locale.ROOT);
		return normalized.contains("group") || normalized.contains("hierarchy");
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
		this.tacticsHintKey = Long.MIN_VALUE;
		this.brushLabelColor = null;
		this.saveLabelCount = -1;
		this.tooltipGuard = null;
		this.tooltipSwatch = null;
		this.tooltipSwatchRow = -1;
		this.hoveredChunkKey = Long.MIN_VALUE;

		this.zonesTab.setLabel(this.font, TAB_ZONES, 10);
		this.groupsTab.setLabel(this.font, TAB_GROUPS, 10);
		this.helpButton.setLabel(this.font, HELP_LABEL, 8);
		this.helpButton.tooltip = TOOLTIP_HELP;
		this.zonesTab.tooltip = TOOLTIP_TAB_ZONES;
		this.groupsTab.tooltip = TOOLTIP_TAB_GROUPS;
		this.revertButton.setLabel(this.font, REVERT, 8);
		this.addGroupButton.setLabel(this.font, ADD_GROUP, 10);
		this.revertButton.tooltip = TOOLTIP_REVERT;
		this.computeLayout();

		this.groupRenameField = new EditBox(this.font, 0, 0, 120, 16, RENAME_HINT);
		this.groupRenameField.setVisible(false);
		this.groupRenameField.setMaxLength(24);
		this.groupRenameField.setHint(RENAME_HINT);
		this.addRenderableWidget(this.groupRenameField);
		this.editingRow = -1;
		this.paletteScrollAnimationFrom = this.paletteScrollOffset;
		this.paletteScrollAnimationTo = this.paletteScrollOffset;
		this.leftPaneScrollAnimated = this.leftPaneScrollTarget;
		this.rightPaneScrollAnimated = this.rightPaneScrollTarget;

		if (this.minecraft != null && this.minecraft.player != null) {
			this.chunkMapWidget.ensureCameraCentered(this.minecraft.player.chunkPosition());
		}
		this.showFirstTimeHelp();
	}

	private void showFirstTimeHelp() {
		if (this.mode == ViewMode.TACTICS && !zonesHelpShown) {
			zonesHelpShown = true;
			this.helpVisible = true;
		} else if (this.mode == ViewMode.GROUPS && !groupsHelpShown) {
			groupsHelpShown = true;
			this.helpVisible = true;
		}
	}

	private void switchMode(ViewMode next) {
		if (this.mode == next) {
			return;
		}
		// Pending group moves live on this screen, so switching views keeps them; no prompt needed.
		this.cancelGroupRename();
		this.dragHandler.cancel();
		this.mode = next;
		this.helpVisible = false;
		this.showFirstTimeHelp();
	}

	// Replaces the container render path on purpose: this menu has no slots, labels or carried item.
	// The screen background is extracted separately by Screen#extractRenderStateWithTooltipAndSubtitles.
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.computeLayout();
		this.checkAwaitingConfirmation();
		graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, PANEL_BACKGROUND);
		graphics.outline(this.panelX, this.panelY, this.panelWidth, this.panelHeight, PANEL_BORDER);

		boolean overlayOpen = this.showUnsavedDialog || this.helpVisible;
		int contentMouseX = overlayOpen ? -1 : mouseX;
		int contentMouseY = overlayOpen ? -1 : mouseY;
		if (this.mode == ViewMode.TACTICS) {
			this.renderTactics(graphics, contentMouseX, contentMouseY);
		} else {
			this.renderGroups(graphics, contentMouseX, contentMouseY);
		}
		this.renderHeader(graphics, contentMouseX, contentMouseY);
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
			this.renderButtonTooltip(graphics, mouseX, mouseY);
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

		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			if (this.isInsideRenameField(mouseX, mouseY)) {
				return this.groupRenameField.mouseClicked(click, doubleClick);
			}
			this.commitGroupRename();
		}

		if (button == 0 && this.handleHeaderClick(mouseX, mouseY)) {
			return true;
		}

		if (this.mode == ViewMode.TACTICS) {
			if (button == 0 && this.hoveredSwatchAt(mouseX, mouseY) != null) {
				this.chunkMapWidget.setActiveColor(this.hoveredSwatchAt(mouseX, mouseY));
				return true;
			}
			if (this.chunkMapWidget.mouseClicked(
				mouseX,
				mouseY,
				button,
				click.hasShiftDown(),
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

		if (this.handleGroupsClick(mouseX, mouseY, button, click.hasShiftDown(), doubleClick)) {
			return true;
		}
		if (this.contains(this.panelX, this.panelY, this.panelWidth, this.panelHeight, mouseX, mouseY)) {
			return true;
		}
		return super.mouseClicked(click, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
		if (this.showUnsavedDialog || this.helpVisible) {
			return true;
		}
		if (this.dragHandler.isArmed()) {
			this.dragHandler.updateDrag(click.x(), click.y());
			return true;
		}
		if (this.mode == ViewMode.TACTICS) {
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
		if (this.mode == ViewMode.TACTICS && this.chunkMapWidget.mouseReleased(click.x(), click.y(), click.button())) {
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (this.showUnsavedDialog || this.helpVisible) {
			return true;
		}
		if (this.mode == ViewMode.TACTICS) {
			if (this.isPaletteHovered(mouseX, mouseY)) {
				int direction = verticalAmount < 0 ? 1 : -1;
				int maxOffset = Math.max(0, RegionColor.paletteCount() - this.visibleSwatches);
				this.startPaletteWheelAnimation(Mth.clamp(this.paletteScrollOffset + direction, 0, maxOffset));
				return true;
			}
			if (this.chunkMapWidget.mouseScrolled(mouseX, mouseY, verticalAmount)) {
				return true;
			}
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}

		int step = (int) Math.round(-verticalAmount * WHEEL_SCROLL_PIXELS);
		if (this.contains(this.leftPaneX, this.leftPaneY, this.leftPaneW, this.leftPaneH, mouseX, mouseY)) {
			this.leftPaneScrollTarget = Mth.clamp(this.leftPaneScrollTarget + step, 0, this.leftPaneMaxScroll);
			return true;
		}
		if (this.contains(this.rightPaneX, this.rightPaneY, this.rightPaneW, this.rightPaneH, mouseX, mouseY)) {
			this.rightPaneScrollTarget = Mth.clamp(this.rightPaneScrollTarget + step, 0, this.rightPaneMaxScroll);
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
			if (this.helpVisible) {
				this.helpVisible = false;
				return true;
			}
			return super.keyPressed(keyInput);
		}
		if (this.helpVisible && key != GLFW.GLFW_KEY_H) {
			this.helpVisible = false;
		}

		if (key == GLFW.GLFW_KEY_H) {
			this.helpVisible = !this.helpVisible;
			return true;
		}
		if (key == GLFW.GLFW_KEY_TAB) {
			this.switchMode(this.mode == ViewMode.TACTICS ? ViewMode.GROUPS : ViewMode.TACTICS);
			return true;
		}

		if (this.mode == ViewMode.TACTICS && this.handleTacticsKey(keyInput)) {
			return true;
		}
		if (this.mode == ViewMode.GROUPS && key == GLFW.GLFW_KEY_S && keyInput.hasControlDown()) {
			this.saveAssignments();
			return true;
		}
		return super.keyPressed(keyInput);
	}

	private boolean handleTacticsKey(KeyEvent keyInput) {
		int key = keyInput.input();
		int digit = keyInput.getDigit();
		if (digit >= 0 && !keyInput.hasControlDown()) {
			RegionColor color = RegionColor.forHotkeyDigit(digit);
			if (color != null) {
				this.chunkMapWidget.setActiveColor(color);
				this.scrollPaletteToShow(color);
				return true;
			}
		}
		ClientTacticsDataStore.WorldContext context = this.worldContext();
		switch (key) {
			case GLFW.GLFW_KEY_C -> this.chunkMapWidget.clearSelection();
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

	private boolean handleHeaderClick(double mouseX, double mouseY) {
		if (this.zonesTab.contains(mouseX, mouseY)) {
			this.switchMode(ViewMode.TACTICS);
			return true;
		}
		if (this.groupsTab.contains(mouseX, mouseY)) {
			this.switchMode(ViewMode.GROUPS);
			return true;
		}
		if (this.helpButton.contains(mouseX, mouseY)) {
			this.helpVisible = !this.helpVisible;
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- header, help, tooltips

	private void renderHeader(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		// Clip the title block so long titles or translations never run under the palette or tabs.
		graphics.enableScissor(this.panelX + 1, this.panelY + 1, Math.max(this.panelX + 2, this.titleAreaRight()), this.panelY + HEADER_HEIGHT);
		graphics.text(this.font, this.title, this.panelX + 10, this.panelY + 6, TEXT_PRIMARY, false);
		graphics.text(
			this.font,
			this.mode == ViewMode.GROUPS ? SUBTITLE_GROUPS : SUBTITLE_ZONES,
			this.panelX + 10,
			this.panelY + 17,
			this.mode == ViewMode.GROUPS ? ACCENT_GROUPS : ACCENT_ZONES,
			false
		);
		graphics.disableScissor();

		boolean zones = this.mode == ViewMode.TACTICS;
		this.drawTab(graphics, this.zonesTab, zones, ACCENT_ZONES, mouseX, mouseY);
		this.drawTab(graphics, this.groupsTab, !zones, ACCENT_GROUPS, mouseX, mouseY);
		this.drawButton(graphics, this.helpButton, mouseX, mouseY, this.helpVisible ? BUTTON_HOVER_FILL : BUTTON_FILL, BUTTON_HOVER_FILL, BUTTON_BORDER);
	}

	private void drawTab(GuiGraphicsExtractor graphics, UiButton tab, boolean active, int accent, int mouseX, int mouseY) {
		boolean hovered = tab.contains(mouseX, mouseY);
		int fill = active ? (accent & 0x00FFFFFF) | 0x55000000 : hovered ? 0xFF2C3A4B : 0xFF1C2532;
		graphics.fill(tab.x, tab.y, tab.x + tab.w, tab.y + tab.h, fill);
		graphics.outline(tab.x, tab.y, tab.w, tab.h, active ? accent : hovered ? CARD_HOVER_BORDER : PANEL_BORDER);
		if (active) {
			graphics.fill(tab.x + 1, tab.y + tab.h - 2, tab.x + tab.w - 1, tab.y + tab.h - 1, accent);
		}
		graphics.text(this.font, tab.label, tab.x + (tab.w - tab.labelWidth) / 2, tab.y + 3, active ? TEXT_PRIMARY : TEXT_SECONDARY, false);
	}

	private void drawButton(GuiGraphicsExtractor graphics, UiButton button, int mouseX, int mouseY, int fill, int hoverFill, int border) {
		if (!button.visible) {
			return;
		}
		boolean hovered = button.enabled && button.contains(mouseX, mouseY);
		graphics.fill(button.x, button.y, button.x + button.w, button.y + button.h, button.enabled ? (hovered ? hoverFill : fill) : DISABLED_FILL);
		graphics.outline(button.x, button.y, button.w, button.h, button.enabled ? (hovered ? TEXT_PRIMARY : border) : DISABLED_BORDER);
		graphics.text(
			this.font,
			button.label,
			button.x + (button.w - button.labelWidth) / 2,
			button.y + (button.h - 8) / 2,
			button.enabled ? TEXT_PRIMARY : TEXT_MUTED,
			false
		);
	}

	private void renderButtonTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		for (UiButton button : this.buttons) {
			if (button.contains(mouseX, mouseY) && !button.tooltip.isEmpty()) {
				graphics.setComponentTooltipForNextFrame(this.font, button.tooltip, mouseX, mouseY);
				return;
			}
		}
	}

	private void renderHelp(GuiGraphicsExtractor graphics) {
		Component[][] rows = this.mode == ViewMode.TACTICS ? HELP_ZONES : HELP_GROUPS;
		Component title = this.mode == ViewMode.TACTICS ? HELP_TITLE_ZONES : HELP_TITLE_GROUPS;
		Component note = this.mode == ViewMode.TACTICS ? HELP_NOTE_ZONES : HELP_NOTE_GROUPS;
		int accent = this.mode == ViewMode.TACTICS ? ACCENT_ZONES : ACCENT_GROUPS;

		int keyWidth = 0;
		int descWidth = 0;
		for (Component[] row : rows) {
			keyWidth = Math.max(keyWidth, this.font.width(row[0]));
			descWidth = Math.max(descWidth, this.font.width(row[1]));
		}
		int lineHeight = 10;
		int maxWidth = this.contentWidth - 12;
		int width = Math.min(maxWidth, keyWidth + descWidth + 28);
		keyWidth = Math.min(keyWidth, width / 2 - 10);
		int descMax = width - keyWidth - 28;
		int height = 22 + rows.length * lineHeight + 26;
		int maxHeight = this.contentHeight - 4;
		int visibleRows = rows.length;
		if (height > maxHeight) {
			visibleRows = Math.max(1, (maxHeight - 48) / lineHeight);
			height = 22 + visibleRows * lineHeight + 26;
		}
		int x = this.contentX + (this.contentWidth - width) / 2;
		int y = this.contentY + Math.max(2, (this.contentHeight - height) / 2);

		graphics.fill(this.contentX, this.contentY, this.contentX + this.contentWidth, this.contentY + this.contentHeight, 0x88000000);
		graphics.fill(x, y, x + width, y + height, 0xF60F151D);
		graphics.outline(x, y, width, height, accent);
		graphics.text(this.font, title, x + 8, y + 7, accent, false);
		int rowY = y + 22;
		for (int i = 0; i < visibleRows; i++) {
			Component[] row = rows[i];
			// Long translations are trimmed per frame here; the legend is a rare overlay, not a hot path.
			graphics.text(this.font, ellipsize(this.font, row[0].getString(), keyWidth), x + 8, rowY, TEXT_PRIMARY, false);
			graphics.text(this.font, ellipsize(this.font, row[1].getString(), descMax), x + 20 + keyWidth, rowY, TEXT_SECONDARY, false);
			rowY += lineHeight;
		}
		graphics.text(this.font, ellipsize(this.font, note.getString(), width - 16), x + 8, y + height - 22, TEXT_MUTED, false);
		graphics.text(this.font, ellipsize(this.font, HELP_DISMISS.getString(), width - 16), x + 8, y + height - 12, TEXT_MUTED, false);
	}

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

	// ---------------------------------------------------------------- tactics view

	private void renderTactics(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (this.groupRenameField != null && this.groupRenameField.isVisible()) {
			this.cancelGroupRename();
		}

		ClientTacticsDataStore.WorldContext worldContext = this.worldContext();
		if (this.minecraft != null && this.minecraft.player != null) {
			this.chunkMapWidget.ensureCameraCentered(this.minecraft.player.chunkPosition());
		}
		boolean shiftDown = this.minecraft != null && this.minecraft.hasShiftDown();

		int mapH = this.contentHeight - BOTTOM_BAR_HEIGHT - 2;
		this.chunkMapWidget.setBounds(this.contentX, this.contentY, this.contentWidth, mapH);
		this.renderPalette(graphics, mouseX, mouseY);
		this.chunkMapWidget.render(graphics, this.font, this.minecraft == null ? null : this.minecraft.level, worldContext, mouseX, mouseY, shiftDown);
		graphics.outline(this.contentX - 1, this.contentY - 1, this.contentWidth + 2, mapH + 2, PANEL_BORDER);
		this.renderPlayerMarker(graphics, mapH);

		if (worldContext != null && this.chunkMapWidget.visibleChunkCount() == 0) {
			boolean anyDiscovered = this.chunkMapWidget.hasDiscoveredChunks();
			this.renderCenteredMessage(
				graphics,
				anyDiscovered ? MAP_OFFSCREEN_TITLE : MAP_EMPTY_TITLE,
				anyDiscovered ? MAP_OFFSCREEN_BODY : MAP_EMPTY_BODY,
				this.contentX,
				this.contentY,
				this.contentWidth,
				mapH
			);
		}

		this.renderTacticsBar(graphics, mouseX, mouseY, shiftDown);
		this.renderTacticsTooltip(graphics, worldContext, mouseX, mouseY);
	}

	private void renderPlayerMarker(GuiGraphicsExtractor graphics, int mapH) {
		if (this.minecraft == null || this.minecraft.player == null) {
			return;
		}
		this.chunkMapWidget.drawPlayerMarker(graphics, this.minecraft.player.getX() / 16.0D, this.minecraft.player.getZ() / 16.0D, this.minecraft.player.getYRot());
	}

	private void renderTacticsBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean shiftDown) {
		graphics.fill(this.contentX, this.barY, this.contentX + this.contentWidth, this.barY + BOTTOM_BAR_HEIGHT, BAR_BACKGROUND);
		int textY = this.barY + 5;

		// Brush chip: always shows what a right-click will do.
		boolean erase = this.chunkMapWidget.isPainting() ? this.chunkMapWidget.isErasing() : shiftDown;
		RegionColor brush = this.chunkMapWidget.activeColor();
		int chipX = this.contentX + 4;
		int chipY = this.barY + 3;
		if (erase) {
			graphics.fill(chipX, chipY, chipX + 12, chipY + 12, 0xFF3A1A1A);
			graphics.outline(chipX, chipY, 12, 12, STATUS_ERROR);
			graphics.text(this.font, "x", chipX + 4, chipY + 2, STATUS_ERROR, false);
		} else {
			graphics.fill(chipX, chipY, chipX + 12, chipY + 12, brush.swatchArgb());
			graphics.outline(chipX, chipY, 12, 12, TEXT_PRIMARY);
			graphics.text(this.font, brush.hotkeyLabel(), chipX + 4, chipY + 2, brush.prefersDarkText() ? 0xFF10161D : 0xFFFFFFFF, false);
		}
		if (this.brushLabelColor != brush) {
			this.brushLabelColor = brush;
			this.brushLabel = Component.translatable(KEY + "brush.paint", brush.labelText());
		}
		Component brushText = erase ? BRUSH_ERASE : this.brushLabel;
		int brushTextX = chipX + 16;
		graphics.text(this.font, brushText, brushTextX, textY, erase ? STATUS_ERROR : TEXT_PRIMARY, false);
		int hintX = brushTextX + this.font.width(brushText) + 10;

		int zoomPercent = (int) Math.round(this.chunkMapWidget.zoom() * 100.0D);
		if (zoomPercent != this.zoomPercentShown) {
			this.zoomPercentShown = zoomPercent;
			this.zoomText = Component.translatable(KEY + "zoom", zoomPercent).getString();
		}
		int zoomWidth = this.font.width(this.zoomText);
		int zoomX = this.contentX + this.contentWidth - zoomWidth - 6;
		graphics.text(this.font, this.zoomText, zoomX, textY, ACCENT_ZONES, false);

		int hintWidth = zoomX - 8 - hintX;
		if (this.hasStatus()) {
			graphics.text(this.font, this.statusFit.fit(this.font, this.statusText, hintWidth), hintX, textY, this.statusColor, false);
		} else {
			Component hint = this.currentTacticsHint(mouseX, mouseY, shiftDown);
			graphics.text(this.font, this.hintFit.fit(this.font, hint, hintWidth), hintX, textY, TEXT_SECONDARY, false);
		}
	}

	// The hint depends on what the cursor is doing; components are rebuilt only when that state changes.
	private Component currentTacticsHint(int mouseX, int mouseY, boolean shiftDown) {
		ChunkMapWidget map = this.chunkMapWidget;
		int state;
		if (map.isPanning()) {
			state = 1;
		} else if (map.isPainting()) {
			state = map.isErasing() ? 2 : 3;
		} else if (map.isSelecting()) {
			state = 4;
		} else if (map.hasSelection()) {
			state = shiftDown ? 5 : 6;
		} else if (map.isMouseOver(mouseX, mouseY)) {
			state = 7;
		} else {
			state = 0;
		}
		long key = ((long) state << 48) | ((long) map.selectionWidth() << 32) | ((long) map.selectionHeight() << 16) | map.activeColor().id();
		if (key != this.tacticsHintKey) {
			this.tacticsHintKey = key;
			this.tacticsHint = switch (state) {
				case 1 -> HINT_PANNING;
				case 2 -> HINT_ERASING;
				case 3 -> HINT_PAINTING;
				case 4 -> Component.translatable(KEY + "hint.selecting", map.selectionWidth(), map.selectionHeight());
				case 5 -> Component.translatable(KEY + "hint.selection_erase", map.selectionWidth(), map.selectionHeight());
				case 6 -> Component.translatable(KEY + "hint.selection", map.selectionWidth(), map.selectionHeight(), map.activeColor().labelText());
				case 7 -> HINT_MAP;
				default -> HINT_IDLE;
			};
		}
		return this.tacticsHint;
	}

	private void renderTacticsTooltip(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		if (this.hoveredSwatch != null) {
			if (this.tooltipSwatch != this.hoveredSwatch) {
				this.tooltipSwatch = this.hoveredSwatch;
				this.swatchTooltip = List.of(
					Component.translatable(KEY + "palette.tooltip", this.hoveredSwatch.labelText(), this.hoveredSwatch.hotkeyLabel()),
					Component.translatable(KEY + "palette.tooltip.body").withStyle(net.minecraft.ChatFormatting.GRAY)
				);
			}
			graphics.setComponentTooltipForNextFrame(this.font, this.swatchTooltip, mouseX, mouseY);
			return;
		}

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
		String groupName = zoneColor == RegionColor.NONE
			? null
			: this.dataStore.groupBindingForColor(worldContext, zoneColor).map(ClientTacticsDataStore.GroupColorBinding::groupName).orElse(null);
		if (this.chunkTooltip.isEmpty() || zoneColor != this.tooltipZoneColor || !java.util.Objects.equals(groupName, this.tooltipGroupName)) {
			this.tooltipZoneColor = zoneColor;
			this.tooltipGroupName = groupName;
			List<Component> lines = new ArrayList<>(3);
			lines.add(Component.translatable(KEY + "chunk.position", hovered.x(), hovered.z()));
			lines.add(zoneColor == RegionColor.NONE
				? tr("chunk.zone_none").copy().withStyle(net.minecraft.ChatFormatting.GRAY)
				: Component.translatable(KEY + "chunk.zone", zoneColor.labelText(), zoneColor.hotkeyLabel()).withStyle(net.minecraft.ChatFormatting.GRAY));
			if (zoneColor != RegionColor.NONE) {
				lines.add(groupName == null
					? tr("chunk.group_none").copy().withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
					: Component.translatable(KEY + "chunk.group", groupName).withStyle(net.minecraft.ChatFormatting.GRAY));
			}
			this.chunkTooltip = lines;
		}
		graphics.setComponentTooltipForNextFrame(this.font, this.chunkTooltip, mouseX, mouseY);
	}

	private void renderPalette(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		RegionColor[] allColors = RegionColor.paletteColors();
		int maxOffset = Math.max(0, allColors.length - this.visibleSwatches);
		if (this.paletteScrollOffset > maxOffset) {
			this.paletteScrollOffset = maxOffset;
			this.paletteScrollAnimationFrom = maxOffset;
			this.paletteScrollAnimationTo = maxOffset;
			this.paletteScrollAnimationStartedAt = 0L;
		}
		double renderedOffset = this.currentPaletteScrollOffset();
		this.hoveredSwatch = this.hoveredSwatchAt(mouseX, mouseY);

		int y = this.paletteClipY;
		int startX = this.paletteClipX;
		graphics.enableScissor(this.paletteClipX, this.paletteClipY - 2, this.paletteClipX + this.paletteClipWidth, this.paletteClipY + this.paletteClipHeight + 2);
		int swatchStride = SWATCH_SIZE + SWATCH_GAP;
		RegionColor active = this.chunkMapWidget.activeColor();
		for (int colorIndex = 0; colorIndex < allColors.length; colorIndex++) {
			int x = startX + (int) Math.round((colorIndex - renderedOffset) * swatchStride);
			if (x + SWATCH_SIZE < this.paletteClipX || x > this.paletteClipX + this.paletteClipWidth) {
				continue;
			}
			RegionColor color = allColors[colorIndex];
			graphics.fill(x, y, x + SWATCH_SIZE, y + SWATCH_SIZE, color.swatchArgb());
			if (color == active) {
				graphics.outline(x - 1, y - 1, SWATCH_SIZE + 2, SWATCH_SIZE + 2, 0xFFFFFFFF);
				graphics.outline(x, y, SWATCH_SIZE, SWATCH_SIZE, 0xFF10161D);
			} else {
				graphics.outline(x, y, SWATCH_SIZE, SWATCH_SIZE, color == this.hoveredSwatch ? TEXT_SECONDARY : PANEL_BORDER);
			}
			graphics.text(this.font, color.hotkeyLabel(), x + 5, y + 3, color.prefersDarkText() ? 0xFF10161D : 0xFFFFFFFF, false);
		}
		graphics.disableScissor();

		// Edge arrows say "more colours this way"; the wheel scrolls them.
		if (renderedOffset > 0.01D) {
			graphics.text(this.font, "<", this.paletteClipX - 7, y + 3, TEXT_SECONDARY, false);
		}
		if (renderedOffset < maxOffset - 0.01D) {
			graphics.text(this.font, ">", this.paletteClipX + this.paletteClipWidth + 2, y + 3, TEXT_SECONDARY, false);
		}
	}

	private RegionColor hoveredSwatchAt(double mouseX, double mouseY) {
		if (this.mode != ViewMode.TACTICS || !this.isPaletteHovered(mouseX, mouseY)) {
			return null;
		}
		int stride = SWATCH_SIZE + SWATCH_GAP;
		double local = mouseX - this.paletteClipX + (this.currentPaletteScrollOffset() * stride);
		int index = (int) Math.floor(local / stride);
		double within = local - (index * (double) stride);
		if (index < 0 || index >= RegionColor.paletteCount() || within >= SWATCH_SIZE) {
			return null;
		}
		return RegionColor.paletteAt(index);
	}

	private void scrollPaletteToShow(RegionColor color) {
		int index = color.paletteIndex();
		if (index < this.paletteScrollOffset) {
			this.startPaletteWheelAnimation(index);
		} else if (index >= this.paletteScrollOffset + this.visibleSwatches) {
			this.startPaletteWheelAnimation(index - this.visibleSwatches + 1);
		}
	}

	private void startPaletteWheelAnimation(int targetOffset) {
		if (targetOffset == this.paletteScrollOffset) {
			return;
		}
		double currentOffset = this.currentPaletteScrollOffset();
		this.paletteScrollOffset = targetOffset;
		this.paletteScrollAnimationFrom = currentOffset;
		this.paletteScrollAnimationTo = targetOffset;
		this.paletteScrollAnimationStartedAt = System.currentTimeMillis();
	}

	private double currentPaletteScrollOffset() {
		if (this.paletteScrollAnimationStartedAt <= 0L) {
			return this.paletteScrollOffset;
		}
		long elapsed = System.currentTimeMillis() - this.paletteScrollAnimationStartedAt;
		if (elapsed >= PALETTE_SCROLL_ANIMATION_MILLIS) {
			this.paletteScrollAnimationStartedAt = 0L;
			return this.paletteScrollOffset;
		}
		double progress = elapsed / (double) PALETTE_SCROLL_ANIMATION_MILLIS;
		double eased = progress * progress * (3.0D - 2.0D * progress);
		return this.paletteScrollAnimationFrom + ((this.paletteScrollAnimationTo - this.paletteScrollAnimationFrom) * eased);
	}

	private boolean isPaletteHovered(double mouseX, double mouseY) {
		return this.mode == ViewMode.TACTICS
			&& this.paletteClipWidth > 0
			&& mouseX >= this.paletteClipX
			&& mouseX < this.paletteClipX + this.paletteClipWidth
			&& mouseY >= this.paletteClipY
			&& mouseY < this.paletteClipY + this.paletteClipHeight;
	}

	// ---------------------------------------------------------------- groups view

	private void refreshRosterView(ClientTacticsDataStore.WorldContext context) {
		int rosterRevision = this.rosterStore.revision();
		if (rosterRevision == this.viewRosterRevision
			&& this.localRevision == this.viewLocalRevision
			&& this.sortMode == this.viewSort
			&& this.filterUnassigned == this.viewFilterUnassigned
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
		this.viewFilterUnassigned = this.filterUnassigned;
		this.viewContext = context;

		List<ClientGuardRosterStore.GuardRosterEntry> roster = this.rosterStore.roster(context);
		this.viewRosterLoaded = this.rosterStore.hasRoster(context);
		this.viewRosterSize = roster.size();
		int maxGroupRow = -1;
		for (ClientGuardRosterStore.GuardRosterEntry guard : roster) {
			maxGroupRow = Math.max(maxGroupRow, this.getEffectiveGroup(guard));
		}
		int groupCount = Mth.clamp(Math.max(maxGroupRow + 1, this.dataStore.groupCount(context)), 0, ClientTacticsDataStore.MAX_GROUPS);
		this.dataStore.ensureGroupCount(context, groupCount);
		this.viewGroupCount = groupCount;

		while (this.groupMembers.size() < groupCount) {
			this.groupMembers.add(new ArrayList<>());
		}
		for (List<ClientGuardRosterStore.GuardRosterEntry> members : this.groupMembers) {
			members.clear();
		}
		this.rosterList.clear();
		for (ClientGuardRosterStore.GuardRosterEntry guard : roster) {
			int group = this.getEffectiveGroup(guard);
			if (group >= 0 && group < groupCount) {
				this.groupMembers.get(group).add(guard);
			}
			if (!this.filterUnassigned || group < 0) {
				this.rosterList.add(guard);
			}
		}
		Comparator<ClientGuardRosterStore.GuardRosterEntry> sorter = this.sortMode.comparator;
		for (int row = 0; row < groupCount; row++) {
			this.groupMembers.get(row).sort(sorter);
		}
		this.rosterList.sort(sorter);

		this.groupNames.clear();
		for (int row = 0; row < groupCount; row++) {
			this.groupNames.add(this.resolveGroupName(context, row, this.groupMembers.get(row)));
		}

		this.guardsHeader = this.viewRosterLoaded
			? Component.translatable(KEY + "guards.header", roster.size())
			: GUARDS_SYNCING;
		this.sortButton.setLabel(this.font, this.sortMode.label, 6);
		this.sortButton.tooltip = this.sortMode.tooltip;
		this.filterButton.setLabel(this.font, this.filterUnassigned ? SortMode.FILTER_UNASSIGNED : SortMode.FILTER_ALL, 6);
		this.filterButton.tooltip = this.filterUnassigned ? TOOLTIP_FILTER_UNASSIGNED : TOOLTIP_FILTER_ALL;
	}

	private void renderGroups(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		// Resolve the drop target against last frame's hitboxes (same geometry) before they are rebuilt.
		this.updateDragTarget(mouseX, mouseY);
		this.resetHits();
		ClientTacticsDataStore.WorldContext worldContext = this.worldContext();
		if (worldContext == null || this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) {
			this.renderCenteredMessage(graphics, UNAVAILABLE, null, this.contentX, this.contentY, this.contentWidth, this.contentHeight);
			return;
		}
		this.refreshRosterView(worldContext);

		int paneH = this.contentHeight - BOTTOM_BAR_HEIGHT - 2;
		this.leftPaneW = (this.contentWidth - PANE_GAP) * LEFT_PANE_RATIO / 100;
		this.rightPaneW = this.contentWidth - PANE_GAP - this.leftPaneW;
		this.leftPaneX = this.contentX;
		this.rightPaneX = this.contentX + this.leftPaneW + PANE_GAP;
		this.leftPaneY = this.contentY;
		this.rightPaneY = this.contentY;
		this.leftPaneH = paneH;
		this.rightPaneH = paneH;

		this.renderGroupsPane(graphics, worldContext, mouseX, mouseY);
		this.renderRosterPane(graphics, worldContext, mouseX, mouseY);
		this.renderGroupsBar(graphics, mouseX, mouseY);

		if (this.editingRow >= 0 && this.groupRenameField != null && !this.groupRenameField.isVisible()) {
			this.cancelGroupRename();
		}
		if (!this.dragHandler.isActive()) {
			this.renderGroupsTooltip(graphics, worldContext, mouseX, mouseY);
		}
	}

	private void renderGroupsPane(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		graphics.fill(this.leftPaneX, this.leftPaneY, this.leftPaneX + this.leftPaneW, this.leftPaneY + this.leftPaneH, PANE_BACKGROUND);
		graphics.outline(this.leftPaneX, this.leftPaneY, this.leftPaneW, this.leftPaneH, ROW_BORDER);
		graphics.text(this.font, GROUPS_HEADER, this.leftPaneX + 6, this.leftPaneY + 4, ACCENT_GROUPS, false);
		if (this.editingRow >= this.viewGroupCount) {
			this.cancelGroupRename();
		}

		this.leftListTop = this.leftPaneY + PANE_HEADER_HEIGHT;
		this.leftListBottom = this.leftPaneY + this.leftPaneH - 2;
		int listH = this.leftListBottom - this.leftListTop;
		int boxX = this.leftPaneX + 4;
		int boxW = this.leftPaneW - 8 - SCROLLBAR_SPACE;
		int cardW = Math.min(MINI_CARD_WIDTH, boxW - 10);
		int perRow = Math.max(1, (boxW - 10 + MINI_CARD_GAP) / (cardW + MINI_CARD_GAP));
		int groupCount = this.viewGroupCount;
		boolean canCreate = groupCount < ClientTacticsDataStore.MAX_GROUPS;
		int newBoxH = groupCount == 0 ? EMPTY_GROUPS_BOX_HEIGHT : NEW_GROUP_BOX_HEIGHT;

		int total = 0;
		for (int row = 0; row < groupCount; row++) {
			total += this.groupBoxHeight(this.groupMembers.get(row).size(), perRow) + GROUP_BOX_GAP;
		}
		if (canCreate) {
			total += newBoxH + GROUP_BOX_GAP;
		}
		this.leftPaneMaxScroll = Math.max(0, total - listH);
		this.autoScrollWhileDragging(true, mouseX, mouseY);
		this.leftPaneScrollTarget = Mth.clamp(this.leftPaneScrollTarget, 0, this.leftPaneMaxScroll);
		this.leftPaneScrollAnimated = lerpPaneScroll(this.leftPaneScrollAnimated, this.leftPaneScrollTarget);
		int scroll = Mth.clamp((int) Math.round(this.leftPaneScrollAnimated), 0, this.leftPaneMaxScroll);

		graphics.enableScissor(this.leftPaneX + 1, this.leftListTop, this.leftPaneX + this.leftPaneW - 1, this.leftListBottom);
		int y = this.leftListTop - scroll;
		for (int row = 0; row < groupCount; row++) {
			int h = this.groupBoxHeight(this.groupMembers.get(row).size(), perRow);
			if (y + h >= this.leftListTop && y <= this.leftListBottom) {
				this.renderGroupBox(graphics, worldContext, row, boxX, y, boxW, h, cardW, perRow, mouseX, mouseY);
			} else if (this.editingRow == row && this.groupRenameField != null) {
				this.groupRenameField.setVisible(false);
			}
			y += h + GROUP_BOX_GAP;
		}
		if (canCreate) {
			this.renderNewGroupBox(graphics, groupCount, boxX, y, boxW, newBoxH, mouseX, mouseY);
		}
		graphics.disableScissor();

		if (this.leftPaneMaxScroll > 0) {
			this.renderScrollbar(graphics, this.leftPaneX + this.leftPaneW - 4, this.leftListTop, 3, listH, scroll, this.leftPaneMaxScroll, total);
			if (scroll < this.leftPaneMaxScroll) {
				graphics.fillGradient(this.leftPaneX + 1, this.leftListBottom - 12, this.leftPaneX + this.leftPaneW - SCROLLBAR_SPACE, this.leftListBottom, 0x00131B25, 0x99131B25);
			}
		}
	}

	private int groupBoxHeight(int memberCount, int perRow) {
		int rows = Math.max(1, (memberCount + perRow - 1) / perRow);
		return GROUP_HEADER_HEIGHT + rows * (MINI_CARD_HEIGHT + MINI_CARD_GAP) + 1;
	}

	private void renderGroupBox(
		GuiGraphicsExtractor graphics,
		ClientTacticsDataStore.WorldContext worldContext,
		int row,
		int x,
		int y,
		int w,
		int h,
		int cardW,
		int perRow,
		int mouseX,
		int mouseY
	) {
		boolean dragging = this.dragHandler.isActive();
		boolean dropHovered = dragging && this.dragTarget != null && this.dragTarget.kind == DropKind.GROUP && this.dragTarget.group == row;
		boolean headerHovered = !dragging && this.inLeftList(mouseX, mouseY) && this.contains(x, y, w, GROUP_HEADER_HEIGHT, mouseX, mouseY);

		graphics.fill(x, y, x + w, y + h, dropHovered ? DROP_HOVER_BACKGROUND : ROW_BACKGROUND);
		if (headerHovered) {
			graphics.fill(x + 1, y + 1, x + w - 1, y + GROUP_HEADER_HEIGHT, ROW_HOVER_BACKGROUND);
		}
		graphics.outline(x, y, w, h, dropHovered ? DROP_HOVER_BORDER : dragging ? DROP_IDLE_BORDER : ROW_BORDER);

		int swatchX = x + 5;
		int swatchY = y + 4;
		RegionColor groupColor = this.dataStore.getGroupColor(worldContext, row);
		boolean swatchHovered = !dragging && this.inLeftList(mouseX, mouseY) && this.contains(swatchX, swatchY, 12, 12, mouseX, mouseY);
		if (groupColor == RegionColor.NONE) {
			graphics.fill(swatchX, swatchY, swatchX + 12, swatchY + 12, 0xFF2C323D);
			graphics.fill(swatchX + 3, swatchY + 6, swatchX + 9, swatchY + 7, TEXT_MUTED);
		} else {
			graphics.fill(swatchX, swatchY, swatchX + 12, swatchY + 12, groupColor.swatchArgb());
			graphics.text(this.font, groupColor.hotkeyLabel(), swatchX + 4, swatchY + 2, groupColor.prefersDarkText() ? 0xFF10161D : 0xFFFFFFFF, false);
		}
		graphics.outline(swatchX, swatchY, 12, 12, swatchHovered ? TEXT_PRIMARY : 0xFF6A7A8D);

		List<ClientGuardRosterStore.GuardRosterEntry> members = this.groupMembers.get(row);
		String countText = this.countText(members.size());
		int countWidth = this.font.width(countText);
		graphics.text(this.font, countText, x + w - countWidth - 6, y + 6, TEXT_MUTED, false);
		int nameX = swatchX + 17;
		int nameMax = x + w - countWidth - 12 - nameX;
		if (this.editingRow == row && this.groupRenameField != null) {
			this.groupRenameField.setVisible(true);
			this.groupRenameField.setX(nameX - 2);
			this.groupRenameField.setY(y + 2);
			this.groupRenameField.setWidth(Math.max(40, x + w - 6 - (nameX - 2)));
		} else {
			graphics.text(this.font, this.fitGroupName(row, nameMax), nameX, y + 6, TEXT_PRIMARY, false);
		}
		this.addRowHit(row, swatchX, swatchY, x, y, w, GROUP_HEADER_HEIGHT);
		this.addDropHit(DropKind.GROUP, row, x, y, w, h);

		int cardsY = y + GROUP_HEADER_HEIGHT;
		if (members.isEmpty()) {
			int textWidth = this.font.width(GROUP_EMPTY);
			graphics.text(this.font, GROUP_EMPTY, x + Math.max(6, (w - textWidth) / 2), cardsY + (MINI_CARD_HEIGHT - 8) / 2, TEXT_MUTED, false);
			return;
		}
		for (int i = 0; i < members.size(); i++) {
			int cx = x + 5 + (i % perRow) * (cardW + MINI_CARD_GAP);
			int cy = cardsY + (i / perRow) * (MINI_CARD_HEIGHT + MINI_CARD_GAP);
			if (cy > this.leftListBottom || cy + MINI_CARD_HEIGHT < this.leftListTop) {
				continue;
			}
			ClientGuardRosterStore.GuardRosterEntry guard = members.get(i);
			this.renderGuardMiniCard(graphics, guard, cx, cy, cardW, mouseX, mouseY);
			this.addCardHit(guard, cx, cy, cardW, MINI_CARD_HEIGHT, row, this.leftListTop, this.leftListBottom);
		}
	}

	private void renderNewGroupBox(GuiGraphicsExtractor graphics, int groupCount, int x, int y, int w, int h, int mouseX, int mouseY) {
		boolean dragging = this.dragHandler.isActive();
		boolean hovered = dragging && this.dragTarget != null && this.dragTarget.kind == DropKind.NEW_GROUP;
		graphics.fill(x, y, x + w, y + h, hovered ? DROP_HOVER_BACKGROUND : DROP_ZONE_BACKGROUND);
		graphics.outline(x, y, w, h, hovered ? DROP_HOVER_BORDER : dragging ? DROP_IDLE_BORDER : DROP_ZONE_BORDER);
		if (groupCount == 0) {
			this.renderCenteredMessage(graphics, GROUPS_NONE_TITLE, GROUPS_NONE_BODY, x, y, w, h);
		} else {
			graphics.text(this.font, this.groupsHintFit.fit(this.font, GROUP_NEW, w - 12), x + 6, y + (h - 8) / 2, TEXT_SECONDARY, false);
		}
		this.addDropHit(DropKind.NEW_GROUP, groupCount, x, y, w, h);
	}

	private void renderRosterPane(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		int paneRight = this.rightPaneX + this.rightPaneW;
		graphics.fill(this.rightPaneX, this.rightPaneY, paneRight, this.rightPaneY + this.rightPaneH, PANE_BACKGROUND);
		graphics.outline(this.rightPaneX, this.rightPaneY, this.rightPaneW, this.rightPaneH, ROW_BORDER);

		// Header: title on the left, sort + filter toggles on the right.
		this.filterButton.place(paneRight - 3 - this.filterButton.w, this.rightPaneY + 2, 11);
		this.sortButton.place(this.filterButton.x - 2 - this.sortButton.w, this.rightPaneY + 2, 11);
		boolean showControls = this.sortButton.x > this.rightPaneX + 40;
		this.sortButton.visible = showControls;
		this.filterButton.visible = showControls;
		this.drawButton(graphics, this.sortButton, mouseX, mouseY, BUTTON_FILL, BUTTON_HOVER_FILL, PANEL_BORDER);
		this.drawButton(graphics, this.filterButton, mouseX, mouseY, this.filterUnassigned ? BUTTON_HOVER_FILL : BUTTON_FILL, BUTTON_HOVER_FILL, this.filterUnassigned ? ACCENT_GROUPS : PANEL_BORDER);
		int headerMax = (showControls ? this.sortButton.x : paneRight) - 4 - (this.rightPaneX + 6);
		graphics.text(this.font, this.guardsHeaderFit.fit(this.font, this.guardsHeader, headerMax), this.rightPaneX + 6, this.rightPaneY + 4, ACCENT_GROUPS, false);

		// Unassign drop zone
		int dropX = this.rightPaneX + 4;
		int dropY = this.rightPaneY + PANE_HEADER_HEIGHT;
		int dropW = this.rightPaneW - 8;
		boolean dragging = this.dragHandler.isActive();
		boolean dropHovered = dragging && this.dragTarget != null && this.dragTarget.kind == DropKind.UNASSIGN;
		graphics.fill(dropX, dropY, dropX + dropW, dropY + UNASSIGN_DROP_HEIGHT, dropHovered ? DROP_HOVER_BACKGROUND : DROP_ZONE_BACKGROUND);
		graphics.outline(dropX, dropY, dropW, UNASSIGN_DROP_HEIGHT, dropHovered ? DROP_HOVER_BORDER : dragging ? DROP_IDLE_BORDER : DROP_ZONE_BORDER);
		graphics.text(this.font, this.unassignFit.fit(this.font, UNASSIGN_TARGET, dropW - 12), dropX + 6, dropY + 5, TEXT_SECONDARY, false);
		this.addDropHit(DropKind.UNASSIGN, -1, dropX, dropY, dropW, UNASSIGN_DROP_HEIGHT);

		this.rightListTop = dropY + UNASSIGN_DROP_HEIGHT + 3;
		this.rightListBottom = this.rightPaneY + this.rightPaneH - 2;
		int listH = this.rightListBottom - this.rightListTop;
		int cardX = this.rightPaneX + 4;
		int cardW = this.rightPaneW - 8 - SCROLLBAR_SPACE;

		List<ClientGuardRosterStore.GuardRosterEntry> list = this.rosterList;
		int total = list.size() * (ROSTER_CARD_HEIGHT + ROSTER_CARD_GAP);
		this.rightPaneMaxScroll = Math.max(0, total - listH);
		this.autoScrollWhileDragging(false, mouseX, mouseY);
		this.rightPaneScrollTarget = Mth.clamp(this.rightPaneScrollTarget, 0, this.rightPaneMaxScroll);
		this.rightPaneScrollAnimated = lerpPaneScroll(this.rightPaneScrollAnimated, this.rightPaneScrollTarget);
		int scroll = Mth.clamp((int) Math.round(this.rightPaneScrollAnimated), 0, this.rightPaneMaxScroll);

		if (list.isEmpty()) {
			Component title;
			Component body;
			if (!this.viewRosterLoaded) {
				title = GUARDS_SYNCING;
				body = GUARDS_SYNCING_BODY;
			} else if (this.viewRosterSize == 0) {
				title = GUARDS_EMPTY_TITLE;
				body = GUARDS_EMPTY_BODY;
			} else {
				title = GUARDS_FILTERED_EMPTY;
				body = null;
			}
			this.renderCenteredMessage(graphics, title, body, this.rightPaneX + 2, this.rightListTop, this.rightPaneW - 4, Math.min(listH, 60));
			return;
		}

		graphics.enableScissor(this.rightPaneX + 1, this.rightListTop, paneRight - 1, this.rightListBottom);
		int first = Math.max(0, scroll / (ROSTER_CARD_HEIGHT + ROSTER_CARD_GAP));
		for (int i = first; i < list.size(); i++) {
			int cardY = this.rightListTop + i * (ROSTER_CARD_HEIGHT + ROSTER_CARD_GAP) - scroll;
			if (cardY > this.rightListBottom) {
				break;
			}
			ClientGuardRosterStore.GuardRosterEntry guard = list.get(i);
			int group = this.getEffectiveGroup(guard);
			this.renderRosterCard(graphics, worldContext, guard, group, cardX, cardY, cardW, mouseX, mouseY);
			this.addCardHit(guard, cardX, cardY, cardW, ROSTER_CARD_HEIGHT, group, this.rightListTop, this.rightListBottom);
		}
		graphics.disableScissor();

		if (this.rightPaneMaxScroll > 0) {
			this.renderScrollbar(graphics, paneRight - 4, this.rightListTop, 3, listH, scroll, this.rightPaneMaxScroll, total);
			if (scroll < this.rightPaneMaxScroll) {
				graphics.fillGradient(this.rightPaneX + 1, this.rightListBottom - 12, paneRight - SCROLLBAR_SPACE, this.rightListBottom, 0x00131B25, 0x99131B25);
			}
		}
	}

	private final FitText unassignFit = new FitText();

	private void renderGroupsBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(this.contentX, this.barY, this.contentX + this.contentWidth, this.barY + BOTTOM_BAR_HEIGHT, BAR_BACKGROUND);
		int buttonY = this.barY + 2;

		boolean canCreate = this.viewGroupCount < ClientTacticsDataStore.MAX_GROUPS;
		this.addGroupButton.place(this.contentX + 2, buttonY, CONTROL_HEIGHT);
		this.addGroupButton.enabled = canCreate;
		this.addGroupButton.tooltip = canCreate
			? TOOLTIP_ADD_GROUP
			: this.groupLimitTooltip();
		this.drawButton(graphics, this.addGroupButton, mouseX, mouseY, BUTTON_FILL, BUTTON_HOVER_FILL, BUTTON_BORDER);

		int pendingCount = this.pendingAssignments.size();
		boolean awaiting = !this.awaitingConfirmation.isEmpty();
		int labelKey = pendingCount > 0 ? pendingCount : awaiting ? -2 : -1;
		if (labelKey != this.saveLabelCount) {
			this.saveLabelCount = labelKey;
			this.saveLabel = pendingCount > 0 ? Component.translatable(KEY + "save", pendingCount) : awaiting ? SAVING : SAVED;
			this.unsavedLabel = pendingCount > 0 ? Component.translatable(KEY + "unsaved", pendingCount) : Component.empty();
			this.saveButton.setLabel(this.font, this.saveLabel, 12);
		}
		int right = this.contentX + this.contentWidth - 2;
		this.saveButton.place(right - this.saveButton.w, buttonY, CONTROL_HEIGHT);
		this.saveButton.enabled = pendingCount > 0;
		this.saveButton.tooltip = TOOLTIP_SAVE;
		this.drawButton(graphics, this.saveButton, mouseX, mouseY, SAVE_FILL, SAVE_HOVER_FILL, SAVE_BORDER);
		right = this.saveButton.x - 4;

		this.revertButton.visible = pendingCount > 0;
		if (this.revertButton.visible) {
			this.revertButton.place(right - this.revertButton.w, buttonY, CONTROL_HEIGHT);
			this.drawButton(graphics, this.revertButton, mouseX, mouseY, BUTTON_FILL, BUTTON_HOVER_FILL, PANEL_BORDER);
			right = this.revertButton.x - 6;
			int unsavedWidth = this.font.width(this.unsavedLabel);
			if (right - unsavedWidth - 8 > this.addGroupButton.x + this.addGroupButton.w + 40) {
				graphics.fill(right - unsavedWidth - 8, this.barY + 7, right - unsavedWidth - 4, this.barY + 11, PENDING_MARKER);
				graphics.text(this.font, this.unsavedLabel, right - unsavedWidth, this.barY + 5, PENDING_MARKER, false);
				right -= unsavedWidth + 14;
			}
		}

		int hintX = this.addGroupButton.x + this.addGroupButton.w + 8;
		int hintWidth = right - hintX;
		if (this.hasStatus()) {
			graphics.text(this.font, this.statusFit.fit(this.font, this.statusText, hintWidth), hintX, this.barY + 5, this.statusColor, false);
		} else {
			Component hint = this.dragHandler.isActive() ? HINT_DRAGGING : this.viewRosterLoaded ? HINT_GROUPS : HINT_SYNCING;
			graphics.text(this.font, this.hintFit.fit(this.font, hint, hintWidth), hintX, this.barY + 5, TEXT_SECONDARY, false);
		}
	}

	private List<Component> groupLimitTooltipCache;

	private List<Component> groupLimitTooltip() {
		if (this.groupLimitTooltipCache == null) {
			this.groupLimitTooltipCache = List.of(Component.translatable(KEY + "add_group.limit", ClientTacticsDataStore.MAX_GROUPS));
		}
		return this.groupLimitTooltipCache;
	}

	private void renderGroupsTooltip(GuiGraphicsExtractor graphics, ClientTacticsDataStore.WorldContext worldContext, int mouseX, int mouseY) {
		for (UiButton button : this.buttons) {
			if (button.contains(mouseX, mouseY)) {
				return;
			}
		}
		for (int i = 0; i < this.rowHitCount; i++) {
			RowHit hit = this.rowHits.get(i);
			if (hit.containsSwatch(mouseX, mouseY)) {
				RegionColor color = this.dataStore.getGroupColor(worldContext, hit.row);
				if (hit.row != this.tooltipSwatchRow || color != this.tooltipSwatchColor) {
					this.tooltipSwatchRow = hit.row;
					this.tooltipSwatchColor = color;
					this.groupSwatchTooltip = List.of(
						color == RegionColor.NONE ? tr("group.swatch.none") : Component.translatable(KEY + "group.swatch", color.labelText()),
						tr("group.swatch.body").copy().withStyle(net.minecraft.ChatFormatting.GRAY),
						tr("group.swatch.link").copy().withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
					);
				}
				graphics.setComponentTooltipForNextFrame(this.font, this.groupSwatchTooltip, mouseX, mouseY);
				return;
			}
		}
		CardHit card = this.findCardHit(mouseX, mouseY);
		if (card != null) {
			this.renderGuardTooltip(graphics, card.guard, card.group, mouseX, mouseY);
			return;
		}
		for (int i = 0; i < this.rowHitCount; i++) {
			RowHit hit = this.rowHits.get(i);
			if (hit.containsHeader(mouseX, mouseY) && this.editingRow != hit.row) {
				graphics.setComponentTooltipForNextFrame(this.font, TOOLTIP_RENAME, mouseX, mouseY);
				return;
			}
		}
	}

	private boolean handleGroupsClick(double mouseX, double mouseY, int button, boolean shiftDown, boolean doubleClick) {
		ClientTacticsDataStore.WorldContext worldContext = this.worldContext();
		if (button == 0 && this.addGroupButton.enabled && this.addGroupButton.contains(mouseX, mouseY)) {
			this.addGroup(worldContext);
			return true;
		}
		if (button == 0 && this.saveButton.enabled && this.saveButton.contains(mouseX, mouseY)) {
			this.saveAssignments();
			return true;
		}
		if (button == 0 && this.revertButton.contains(mouseX, mouseY)) {
			this.revertAssignments();
			return true;
		}
		if (button == 0 && this.sortButton.contains(mouseX, mouseY)) {
			this.sortMode = this.sortMode.next();
			return true;
		}
		if (button == 0 && this.filterButton.contains(mouseX, mouseY)) {
			this.filterUnassigned = !this.filterUnassigned;
			this.rightPaneScrollTarget = 0;
			return true;
		}
		if (worldContext == null) {
			return false;
		}

		for (int i = 0; i < this.rowHitCount; i++) {
			RowHit row = this.rowHits.get(i);
			if (row.containsSwatch(mouseX, mouseY)) {
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
				return true;
			}
			if (row.containsHeader(mouseX, mouseY) && ((button == 1 && shiftDown) || (button == 0 && doubleClick))) {
				this.startGroupRename(row.row, this.groupNames.get(row.row));
				return true;
			}
		}

		if (button == 0) {
			CardHit card = this.findCardHit(mouseX, mouseY);
			if (card != null) {
				this.dragHandler.arm(card.guard, card.group, card.group < 0, mouseX, mouseY);
				return true;
			}
		}
		return false;
	}

	private void addGroup(ClientTacticsDataStore.WorldContext worldContext) {
		if (worldContext == null || this.dataStore.groupCount(worldContext) >= ClientTacticsDataStore.MAX_GROUPS) {
			return;
		}
		int nextRow = this.dataStore.groupCount(worldContext);
		this.dataStore.ensureGroupCount(worldContext, nextRow + 1);
		this.localRevision++;
		if (this.sendCommand("guards groups add")) {
			this.setStatus(Component.translatable(KEY + "status.group_added", nextRow + 1), STATUS_SUCCESS);
		}
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
		int currentGroup = guard == null ? -1 : this.getEffectiveGroup(guard);
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

	private final DropHit dragTargetScratch = new DropHit();

	private void autoScrollWhileDragging(boolean leftPane, int mouseX, int mouseY) {
		if (!this.dragHandler.isActive()) {
			return;
		}
		int paneX = leftPane ? this.leftPaneX : this.rightPaneX;
		int paneW = leftPane ? this.leftPaneW : this.rightPaneW;
		int top = leftPane ? this.leftListTop : this.rightListTop;
		int bottom = leftPane ? this.leftListBottom : this.rightListBottom;
		if (mouseX < paneX || mouseX >= paneX + paneW) {
			return;
		}
		int delta = 0;
		if (mouseY >= top - 4 && mouseY < top + DRAG_AUTOSCROLL_EDGE) {
			delta = -DRAG_AUTOSCROLL_SPEED;
		} else if (mouseY > bottom - DRAG_AUTOSCROLL_EDGE && mouseY <= bottom + 4) {
			delta = DRAG_AUTOSCROLL_SPEED;
		}
		if (delta == 0) {
			return;
		}
		if (leftPane) {
			this.leftPaneScrollTarget = Mth.clamp(this.leftPaneScrollTarget + delta, 0, this.leftPaneMaxScroll);
		} else {
			this.rightPaneScrollTarget = Mth.clamp(this.rightPaneScrollTarget + delta, 0, this.rightPaneMaxScroll);
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
			if (worldContext == null || targetGroup >= ClientTacticsDataStore.MAX_GROUPS) {
				return;
			}
			this.dataStore.ensureGroupCount(worldContext, targetGroup + 1);
			// Create it on the server too, like the Add Group button, so names and counts stay in sync.
			this.sendCommand("guards groups add");
		}
		this.setPendingAssignment(result.guard(), targetGroup);
	}

	private void setPendingAssignment(ClientGuardRosterStore.GuardRosterEntry guard, int targetGroup) {
		UUID guardId = guard.guardUuid();
		this.awaitingConfirmation.remove(guardId);
		// Moving a guard back to where the server has it is not a change.
		if (targetGroup == guard.groupIndex()) {
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
		for (Map.Entry<UUID, Integer> entry : this.pendingAssignments.entrySet()) {
			this.minecraft.getConnection().sendCommand("guards groups assign " + entry.getKey() + " " + (entry.getValue() + 1));
		}
		// Each accepted assign makes the server push a fresh roster; confirmation is checked against it.
		this.awaitingConfirmation.putAll(this.pendingAssignments);
		this.awaitingSentCount = this.awaitingConfirmation.size();
		this.awaitingSince = System.currentTimeMillis();
		this.awaitingCheckedRevision = this.rosterStore.revision();
		this.pendingAssignments.clear();
		this.localRevision++;
		this.setStatus(Component.translatable(KEY + "status.saving", this.awaitingSentCount), TEXT_SECONDARY);
	}

	private void checkAwaitingConfirmation() {
		if (this.awaitingConfirmation.isEmpty()) {
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
			// The server answers failed assigns with a chat error and no roster update, so silence means rejected.
			int rejected = this.awaitingConfirmation.size();
			this.awaitingConfirmation.clear();
			this.localRevision++;
			this.setStatus(Component.translatable(KEY + "status.rejected", rejected, this.awaitingSentCount), STATUS_ERROR);
		}
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
		if (worldContext == null || requested.isEmpty() || requested.equals(this.groupNames.size() > row ? this.groupNames.get(row) : null)) {
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

	// ---------------------------------------------------------------- cards

	private void renderGuardMiniCard(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int w, int mouseX, int mouseY) {
		boolean isSource = this.dragHandler.isActive() && this.dragHandler.draggedGuard() == guard;
		boolean hovered = !this.dragHandler.isActive() && this.inLeftList(mouseX, mouseY) && this.contains(x, y, w, MINI_CARD_HEIGHT, mouseX, mouseY);
		graphics.fill(x, y, x + w, y + MINI_CARD_HEIGHT, hovered ? CARD_HOVER_BACKGROUND : CARD_BACKGROUND);
		graphics.outline(x, y, w, MINI_CARD_HEIGHT, hovered ? CARD_HOVER_BORDER : CARD_BORDER);
		if (this.pendingAssignments.containsKey(guard.guardUuid())) {
			graphics.fill(x + 1, y + 1, x + 3, y + MINI_CARD_HEIGHT - 1, PENDING_MARKER);
		}
		graphics.item(GUARD_HEAD_ICON, x + 3, y + 2);
		CardText text = this.cardText(guard);
		graphics.text(this.font, text.miniName(this.font, w - 22), x + 20, y + 3, TEXT_PRIMARY, false);
		graphics.text(this.font, levelText(guard.level()), x + 20, y + 12, TEXT_SECONDARY, false);
		if (!text.distance.isEmpty() && w >= 70) {
			graphics.text(this.font, text.distance, x + w - text.distanceWidth - 3, y + 12, TEXT_MUTED, false);
		}
		int armorY = y + 21;
		this.drawMiniArmorIcon(graphics, guard.helmet(), x + 3, armorY);
		this.drawMiniArmorIcon(graphics, guard.chest(), x + 20, armorY);
		this.drawMiniArmorIcon(graphics, guard.legs(), x + 37, armorY);
		this.drawMiniArmorIcon(graphics, guard.boots(), x + 54, armorY);
		this.drawHealthBar(graphics, guard, x + 2, y + MINI_CARD_HEIGHT - 3, w - 4, 2);
		if (isSource) {
			graphics.fill(x, y, x + w, y + MINI_CARD_HEIGHT, 0xAA0E141B);
		}
	}

	private void drawMiniArmorIcon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		if (stack == null || stack.isEmpty()) {
			graphics.fill(x + 2, y + 2, x + 12, y + 12, 0x444D5F72);
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate((float) x, (float) y);
		graphics.pose().scale(0.875F, 0.875F);
		graphics.item(stack, 0, 0);
		graphics.pose().popMatrix();
	}

	private void renderRosterCard(
		GuiGraphicsExtractor graphics,
		ClientTacticsDataStore.WorldContext worldContext,
		ClientGuardRosterStore.GuardRosterEntry guard,
		int group,
		int x,
		int y,
		int w,
		int mouseX,
		int mouseY
	) {
		boolean isSource = this.dragHandler.isActive() && this.dragHandler.draggedGuard() == guard;
		boolean hovered = !this.dragHandler.isActive()
			&& mouseY >= this.rightListTop && mouseY < this.rightListBottom
			&& this.contains(x, y, w, ROSTER_CARD_HEIGHT, mouseX, mouseY);
		graphics.fill(x, y, x + w, y + ROSTER_CARD_HEIGHT, hovered ? CARD_HOVER_BACKGROUND : CARD_BACKGROUND);
		graphics.outline(x, y, w, ROSTER_CARD_HEIGHT, hovered ? CARD_HOVER_BORDER : CARD_BORDER);
		if (this.pendingAssignments.containsKey(guard.guardUuid())) {
			graphics.fill(x + 1, y + 1, x + 3, y + ROSTER_CARD_HEIGHT - 1, PENDING_MARKER);
		}
		graphics.item(GUARD_HEAD_ICON, x + 3, y + 5);

		CardText text = this.cardText(guard);
		String level = levelText(guard.level());
		int levelWidth = this.font.width(level);
		graphics.text(this.font, level, x + w - levelWidth - 4, y + 4, TEXT_SECONDARY, false);
		graphics.text(this.font, text.rowName(this.font, w - 22 - levelWidth - 8), x + 22, y + 4, TEXT_PRIMARY, false);

		// Second line: health bar, group chip, distance.
		int lineY = y + 15;
		int barW = Mth.clamp(w / 5, 16, 36);
		this.drawHealthBar(graphics, guard, x + 22, lineY + 2, barW, 4);
		int distanceX = x + w - 4;
		if (!text.distance.isEmpty()) {
			distanceX -= text.distanceWidth;
			graphics.text(this.font, text.distance, distanceX, lineY, TEXT_MUTED, false);
		}
		int chipX = x + 22 + barW + 5;
		int chipMax = distanceX - 4 - (chipX + 8);
		if (chipMax > 12) {
			RegionColor groupColor = group >= 0 ? this.dataStore.getGroupColor(worldContext, group) : RegionColor.NONE;
			graphics.fill(chipX, lineY + 1, chipX + 6, lineY + 7, group < 0 ? 0xFF3A4654 : groupColor == RegionColor.NONE ? 0xFF6A7A8D : groupColor.swatchArgb());
			String groupName = group < 0 ? text.unassigned(this.font, chipMax) : this.fitGroupName(group, chipMax);
			graphics.text(this.font, groupName, chipX + 8, lineY, group < 0 ? TEXT_MUTED : TEXT_SECONDARY, false);
		}
		if (isSource) {
			graphics.fill(x, y, x + w, y + ROSTER_CARD_HEIGHT, 0xAA0E141B);
		}
	}

	private void drawHealthBar(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int w, int h) {
		if (guard.maxHealth() <= 0.0F || w <= 0) {
			return;
		}
		float ratio = Mth.clamp(guard.health() / guard.maxHealth(), 0.0F, 1.0F);
		int color = ratio > 0.6F ? 0xFF4CC26A : ratio > 0.3F ? 0xFFE0B341 : 0xFFE05252;
		graphics.fill(x, y, x + w, y + h, 0xFF0E141B);
		graphics.fill(x, y, x + Math.max(1, Math.round(w * ratio)), y + h, color);
	}

	private CardText cardText(ClientGuardRosterStore.GuardRosterEntry guard) {
		CardText text = this.cardTexts.get(guard.guardUuid());
		if (text == null) {
			text = new CardText(guard, this.font);
			this.cardTexts.put(guard.guardUuid(), text);
		}
		return text;
	}

	private String countText(int count) {
		return this.countCache.get(count, this.font);
	}

	private final CountCache countCache = new CountCache();

	private String fitGroupName(int row, int maxWidth) {
		if (row < 0 || row >= this.groupNames.size()) {
			return "";
		}
		return this.groupNameFits.fit(this.font, row, this.groupNames.get(row), maxWidth);
	}

	private final GroupNameFits groupNameFits = new GroupNameFits();

	private void renderGuardTooltip(GuiGraphicsExtractor graphics, ClientGuardRosterStore.GuardRosterEntry guard, int group, int mouseX, int mouseY) {
		if (guard != this.tooltipGuard || group != this.tooltipGuardGroup) {
			this.tooltipGuard = guard;
			this.tooltipGuardGroup = group;
			this.guardTooltip = this.buildGuardTooltip(guard, group);
		}
		graphics.setComponentTooltipForNextFrame(this.font, this.guardTooltip, mouseX, mouseY);
	}

	private List<Component> buildGuardTooltip(ClientGuardRosterStore.GuardRosterEntry guard, int group) {
		List<Component> tooltip = new ArrayList<>(8);
		tooltip.add(Component.literal(guard.displayName()));
		if (guard.maxHealth() > 0.0F) {
			tooltip.add(Component.translatable(KEY + "guard.health", Mth.ceil(guard.health()), Mth.ceil(guard.maxHealth())).withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		if (guard.experience() >= 0) {
			tooltip.add(guard.level() >= 10
				? Component.translatable(KEY + "guard.level_max", guard.level()).withStyle(net.minecraft.ChatFormatting.GRAY)
				: Component.translatable(KEY + "guard.level", guard.level(), guard.experience(), guard.level() * 120).withStyle(net.minecraft.ChatFormatting.GRAY));
		} else {
			tooltip.add(Component.literal(levelText(guard.level())).withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		ItemStack weapon = guard.mainHand();
		Component weaponName = weapon == null || weapon.isEmpty() ? tr("guard.none") : weapon.getHoverName();
		tooltip.add(Component.translatable(KEY + "guard.weapon", weaponName, weaponDamage(weapon)).withStyle(net.minecraft.ChatFormatting.GRAY));
		int totalArmor = armorPoints(guard.helmet()) + armorPoints(guard.chest()) + armorPoints(guard.legs()) + armorPoints(guard.boots());
		tooltip.add(Component.translatable(KEY + "guard.armor", totalArmor).withStyle(net.minecraft.ChatFormatting.GRAY));
		tooltip.add(guard.distance() >= 0.0D
			? Component.translatable(KEY + "guard.distance", String.format(Locale.ROOT, "%.1f", guard.distance())).withStyle(net.minecraft.ChatFormatting.GRAY)
			: tr("guard.distance_unknown").copy().withStyle(net.minecraft.ChatFormatting.GRAY));
		String groupName = group < 0 ? UNASSIGNED.getString() : (group < this.groupNames.size() ? this.groupNames.get(group) : guard.groupName());
		boolean pending = this.pendingAssignments.containsKey(guard.guardUuid());
		tooltip.add(Component.translatable(KEY + (pending ? "guard.group_pending" : "guard.group"), groupName)
			.withStyle(pending ? net.minecraft.ChatFormatting.YELLOW : net.minecraft.ChatFormatting.GRAY));
		tooltip.add(GUARD_DRAG_HINT);
		return tooltip;
	}

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
		this.drawDialogButton(graphics, DIALOG_SAVE, dx + 10, buttonY, buttonW, mouseX, mouseY, SAVE_FILL, SAVE_HOVER_FILL, SAVE_BORDER);
		this.drawDialogButton(graphics, DIALOG_DISCARD, dx + 20 + buttonW, buttonY, buttonW, mouseX, mouseY, 0xFF3A1A1A, 0xFF5A2A2A, 0xFFAA4444);
		this.drawDialogButton(graphics, DIALOG_CANCEL, dx + 30 + buttonW * 2, buttonY, buttonW, mouseX, mouseY, BUTTON_FILL, BUTTON_HOVER_FILL, PANEL_BORDER);
	}

	private void drawDialogButton(GuiGraphicsExtractor graphics, Component label, int x, int y, int w, int mouseX, int mouseY, int fill, int hoverFill, int border) {
		boolean hovered = this.contains(x, y, w, DIALOG_BUTTON_HEIGHT, mouseX, mouseY);
		graphics.fill(x, y, x + w, y + DIALOG_BUTTON_HEIGHT, hovered ? hoverFill : fill);
		graphics.outline(x, y, w, DIALOG_BUTTON_HEIGHT, hovered ? TEXT_PRIMARY : border);
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
		if (this.minecraft != null && this.minecraft.getConnection() != null) {
			this.minecraft.getConnection().sendCommand(command);
			return true;
		}
		this.setStatus(tr("status.no_connection"), STATUS_ERROR);
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
		}
		return this.cachedContext;
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

	private static double lerpPaneScroll(double current, int target) {
		if (Math.abs(target - current) <= 0.5D) {
			return target;
		}
		return current + ((target - current) * PANE_SCROLL_LERP_FACTOR);
	}

	private boolean inLeftList(double mouseX, double mouseY) {
		return mouseY >= this.leftListTop && mouseY < this.leftListBottom;
	}

	private boolean contains(int x, int y, int width, int height, double mouseX, double mouseY) {
		return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
	}

	private int titleAreaRight() {
		return this.mode == ViewMode.TACTICS && this.paletteClipWidth > 0 ? this.paletteClipX - 10 : this.helpButton.x - 6;
	}

	private void computeLayout() {
		// Tighter margins on tiny GUIs (e.g. 320x240 scaled) so the map and panes keep usable space.
		int margin = this.width < 420 || this.height < 280 ? 4 : 10;
		this.panelX = margin;
		this.panelY = margin;
		this.panelWidth = this.width - margin * 2;
		this.panelHeight = this.height - margin * 2;
		this.contentX = this.panelX + 6;
		this.contentY = this.panelY + HEADER_HEIGHT + 2;
		this.contentWidth = this.panelWidth - 12;
		this.contentHeight = this.panelHeight - HEADER_HEIGHT - 6;
		this.barY = this.contentY + this.contentHeight - BOTTOM_BAR_HEIGHT;

		boolean groups = this.mode == ViewMode.GROUPS;
		this.addGroupButton.visible = groups;
		this.saveButton.visible = groups;
		this.sortButton.visible = groups;
		this.filterButton.visible = groups;
		this.revertButton.visible = groups && !this.pendingAssignments.isEmpty();

		int controlY = this.panelY + 7;
		int right = this.panelX + this.panelWidth - 8;
		this.groupsTab.place(right - this.groupsTab.w, controlY, CONTROL_HEIGHT);
		this.zonesTab.place(this.groupsTab.x - this.zonesTab.w, controlY, CONTROL_HEIGHT);
		this.helpButton.place(this.zonesTab.x - 6 - this.helpButton.w, controlY, CONTROL_HEIGHT);

		if (this.mode == ViewMode.TACTICS) {
			int paletteRight = this.helpButton.x - 12;
			int titleRight = this.panelX + 10 + Math.min(this.font == null ? 60 : this.font.width(this.title), 90) + 14;
			int available = paletteRight - titleRight;
			int stride = SWATCH_SIZE + SWATCH_GAP;
			this.visibleSwatches = Mth.clamp((available + SWATCH_GAP) / stride, 1, RegionColor.paletteCount());
			this.paletteClipWidth = this.visibleSwatches * stride - SWATCH_GAP;
			this.paletteClipX = paletteRight - this.paletteClipWidth;
			this.paletteClipY = controlY;
			this.paletteClipHeight = SWATCH_SIZE;
		} else {
			this.paletteClipWidth = 0;
		}
	}

	private void resetHits() {
		this.cardHitCount = 0;
		this.rowHitCount = 0;
		this.dropHitCount = 0;
	}

	// Hitboxes are clipped to their scroll viewport so cards scrolled out of view cannot be grabbed.
	private void addCardHit(ClientGuardRosterStore.GuardRosterEntry guard, int x, int y, int w, int h, int group, int clipTop, int clipBottom) {
		int top = Math.max(y, clipTop);
		int bottom = Math.min(y + h, clipBottom);
		if (bottom <= top) {
			return;
		}
		if (this.cardHitCount == this.cardHits.size()) {
			this.cardHits.add(new CardHit());
		}
		CardHit hit = this.cardHits.get(this.cardHitCount++);
		hit.guard = guard;
		hit.x = x;
		hit.y = top;
		hit.w = w;
		hit.h = bottom - top;
		hit.group = group;
	}

	private void addRowHit(int row, int swatchX, int swatchY, int x, int y, int w, int h) {
		int top = Math.max(y, this.leftListTop);
		int bottom = Math.min(y + h, this.leftListBottom);
		if (bottom <= top) {
			return;
		}
		if (this.rowHitCount == this.rowHits.size()) {
			this.rowHits.add(new RowHit());
		}
		RowHit hit = this.rowHits.get(this.rowHitCount++);
		hit.row = row;
		hit.swatchX = swatchX;
		hit.swatchY = swatchY;
		hit.x = x;
		hit.y = top;
		hit.w = w;
		hit.h = bottom - top;
	}

	private void addDropHit(DropKind kind, int group, int x, int y, int w, int h) {
		int top = y;
		int bottom = y + h;
		if (kind != DropKind.UNASSIGN) {
			top = Math.max(top, this.leftListTop);
			bottom = Math.min(bottom, this.leftListBottom);
		}
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

	private enum ViewMode {
		TACTICS,
		GROUPS
	}

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

		private static final Component FILTER_ALL = tr("filter.all");
		private static final Component FILTER_UNASSIGNED = tr("filter.unassigned");

		private final Component label;
		private final List<Component> tooltip;
		private final Comparator<ClientGuardRosterStore.GuardRosterEntry> comparator;

		SortMode(String id, Comparator<ClientGuardRosterStore.GuardRosterEntry> primary) {
			this.label = tr("sort." + id);
			this.tooltip = List.of(
				Component.translatable(KEY + "sort.tooltip", tr("sort." + id)),
				tr("sort.tooltip.body").copy().withStyle(net.minecraft.ChatFormatting.GRAY)
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

		private void setLabel(Font font, Component label, int padding) {
			this.label = label;
			this.labelWidth = font.width(label);
			this.w = this.labelWidth + padding;
		}

		private void place(int x, int y, int h) {
			this.x = x;
			this.y = y;
			this.h = h;
		}

		private boolean contains(double mouseX, double mouseY) {
			return this.visible && this.label != null && mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.w && mouseY < this.y + this.h;
		}
	}

	private static final class CardHit {
		private ClientGuardRosterStore.GuardRosterEntry guard;
		private int x;
		private int y;
		private int w;
		private int h;
		private int group;
	}

	private static final class RowHit {
		private int row;
		private int swatchX;
		private int swatchY;
		private int x;
		private int y;
		private int w;
		private int h;

		private boolean containsSwatch(double mouseX, double mouseY) {
			return mouseX >= this.swatchX && mouseY >= Math.max(this.swatchY, this.y)
				&& mouseX < this.swatchX + 12 && mouseY < Math.min(this.swatchY + 12, this.y + this.h);
		}

		private boolean containsHeader(double mouseX, double mouseY) {
			return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.w && mouseY < this.y + this.h;
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
		private final String distance;
		private final int distanceWidth;
		private int miniWidth = Integer.MIN_VALUE;
		private String miniName = "";
		private int rowWidth = Integer.MIN_VALUE;
		private String rowName = "";
		private int unassignedWidth = Integer.MIN_VALUE;
		private String unassigned = "";

		private CardText(ClientGuardRosterStore.GuardRosterEntry guard, Font font) {
			this.name = guard.displayName();
			this.distance = guard.distance() >= 0.0D
				? Component.translatable(KEY + "distance", (int) Math.round(guard.distance())).getString()
				: "";
			this.distanceWidth = font.width(this.distance);
		}

		private String miniName(Font font, int width) {
			if (width != this.miniWidth) {
				this.miniWidth = width;
				this.miniName = ellipsize(font, this.name, width);
			}
			return this.miniName;
		}

		private String rowName(Font font, int width) {
			if (width != this.rowWidth) {
				this.rowWidth = width;
				this.rowName = ellipsize(font, this.name, width);
			}
			return this.rowName;
		}

		private String unassigned(Font font, int width) {
			if (width != this.unassignedWidth) {
				this.unassignedWidth = width;
				this.unassigned = ellipsize(font, UNASSIGNED.getString(), width);
			}
			return this.unassigned;
		}
	}

	/** "N guards" strings by count. */
	private static final class CountCache {
		private final Int2ObjectOpenHashMap<String> values = new Int2ObjectOpenHashMap<>();

		private String get(int count, Font font) {
			String value = this.values.get(count);
			if (value == null) {
				value = (count == 1 ? tr("group.count.one") : Component.translatable(KEY + "group.count", count)).getString();
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
