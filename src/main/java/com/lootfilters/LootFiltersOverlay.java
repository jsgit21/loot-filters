package com.lootfilters;

import net.runelite.api.Client;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

import static com.lootfilters.util.TextUtil.getValueText;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static net.runelite.api.Perspective.getCanvasTextLocation;
import static net.runelite.client.ui.FontManager.getRunescapeSmallFont;

public class LootFiltersOverlay extends Overlay {
    private static final int Z_STACK_OFFSET = 16; // for initial perspective and subsequent vertical stack
    private static final int BOX_PAD = 2;
    private static final Color COLOR_HIDDEN = Color.GRAY.brighter();

    private final Client client;
    private final LootFiltersPlugin plugin;
    private final LootFiltersConfig config;

    @Inject
    private ItemManager itemManager;

    @Inject
    public LootFiltersOverlay(Client client, LootFiltersPlugin plugin, LootFiltersConfig config) {
        setPosition(OverlayPosition.DYNAMIC);
        this.client = client;
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!plugin.isOverlayEnabled()) {
            return null;
        }

        var activeFilter = plugin.getActiveFilter();
        var mouse = client.getMouseCanvasPosition();
        var hoveredItem = -1;

        for (var entry : plugin.getTileItemIndex().entrySet()) {
            var items = entry.getValue();
            var itemCounts = items.stream()
                    .collect(groupingBy(TileItem::getId, counting()));

            var tile = entry.getKey();
            var currentOffset = 0;
            for (var id : itemCounts.keySet()) {
                var count = itemCounts.get(id);
                var item = items.stream()
                        .filter(it -> it.getId() == id)
                        .findFirst().orElseThrow();

                var match = activeFilter.findMatch(plugin, item);
                if (match == null) {
                    continue;
                }

                var overrideHidden = plugin.isHotkeyActive() && plugin.getConfig().hotkeyShowHiddenItems();
                if (match.isHidden() && !overrideHidden) {
                    continue;
                }

                var loc = LocalPoint.fromWorld(client.getTopLevelWorldView(), tile.getWorldLocation());
                if (loc == null) {
                    continue;
                }
                if (tile.getItemLayer() == null) {
                    continue;
                }

                var displayText = buildDisplayText(item, count, match);
                var textPoint = getCanvasTextLocation(client, g, loc, displayText, tile.getItemLayer().getHeight() + Z_STACK_OFFSET);
                if (textPoint == null) {
                    continue;
                }

                var fm = g.getFontMetrics(getRunescapeSmallFont());
                var textWidth = fm.stringWidth(displayText);
                var textHeight = fm.getHeight();

                var text = new TextComponent();
                text.setText(displayText);
                text.setFont(getRunescapeSmallFont());
                text.setColor(match.isHidden() ? COLOR_HIDDEN : match.getTextColor());
                text.setPosition(new Point(textPoint.getX(), textPoint.getY() - currentOffset));

                var boundingBox = new Rectangle(
                        textPoint.getX() - BOX_PAD, textPoint.getY() - currentOffset - textHeight - BOX_PAD,
                        textWidth + 2 * BOX_PAD, textHeight + 2 * BOX_PAD
                );

                if (match.getBackgroundColor() != null) {
                    g.setColor(match.getBackgroundColor());
                    g.fillRect(boundingBox.x, boundingBox.y, boundingBox.width, boundingBox.height);
                }
                if (match.getBorderColor() != null) {
                    g.setColor(match.getBorderColor());
                    g.drawRect(boundingBox.x, boundingBox.y, boundingBox.width, boundingBox.height);
                }
                if (plugin.isHotkeyActive() && boundingBox.contains(mouse.getX(), mouse.getY())) {
                    hoveredItem = item.getId();

                    g.setColor(match.isHidden() ? COLOR_HIDDEN : Color.WHITE);
                    g.drawRect(boundingBox.x, boundingBox.y, boundingBox.width, boundingBox.height);
                }

                text.render(g);

                if (match.isShowDespawn()) {
                    var ticksRemaining = item.getDespawnTime() - client.getTickCount();
                    if (ticksRemaining < 0) { // doesn't despawn
                        continue;
                    }
                    text.setColor(getDespawnTextColor(item));
                    text.setText(Integer.toString(ticksRemaining));
                    text.setPosition(new Point(textPoint.getX() + textWidth + 2 + 1, textPoint.getY() - currentOffset));
                    text.render(g);
                }

                currentOffset += Z_STACK_OFFSET;
            }
        }

        plugin.setHoveredItem(hoveredItem);
        return null;
    }

    private Color getDespawnTextColor(TileItem item) {
        if (item.getDespawnTime() - client.getTickCount() < 100) {
            return Color.RED;
        }
        if (item.getVisibleTime() <= client.getTickCount()) {
            return Color.YELLOW;
        }
        return Color.GREEN;
    }

    private String buildDisplayText(TileItem item, long unstackedCount, DisplayConfig display) {
        var text = itemManager.getItemComposition(item.getId()).getName();

        if (item.getQuantity() > 1) {
            text += " (" + item.getQuantity() + ")";
        } else if (unstackedCount > 1) {
            text += " x" + unstackedCount; // we want these to be visually different
        }

        if (display.isShowValue()) {
            var ge = itemManager.getItemPrice(item.getId());
            var ha = itemManager.getItemComposition(item.getId()).getHaPrice();
            var value = Math.max(ge, ha) * item.getQuantity();
            if (value > 0) {
                text += " (";
                if (ha > ge) {
                    text += "*";
                }
                text += getValueText(value) + ")";
            }
        }

        return text;
    }
}
