package inceris.deluxeanvil;

import com.extendedclip.deluxemenus.menu.MenuHolder;
import com.extendedclip.deluxemenus.menu.MenuItem;
import com.extendedclip.deluxemenus.menu.options.MenuItemOptions;
import com.extendedclip.deluxemenus.requirement.RequirementList;
import java.util.List;
import java.util.Optional;
import org.bukkit.ChatColor;

/** A per-viewer copy; the shared DeluxeMenus item and requirements are untouched. */
final class ValidatedConfirmation extends MenuItem {
    ValidatedConfirmation(MenuHolder holder, MenuItem original, AnvilInput input, AnvilValidation validation) {
        super(holder.getPlugin(), guardedOptions(original.options(), input, validation));
    }

    private static MenuItemOptions guardedOptions(MenuItemOptions options, AnvilInput input,
                                                    AnvilValidation validation) {
        return options.asBuilder()
            .clickRequirements(guard(options.clickRequirements(), input, validation))
            .leftClickRequirements(guard(options.leftClickRequirements(), input, validation))
            .rightClickRequirements(guard(options.rightClickRequirements(), input, validation))
            .shiftLeftClickRequirements(guard(options.shiftLeftClickRequirements(), input, validation))
            .shiftRightClickRequirements(guard(options.shiftRightClickRequirements(), input, validation))
            .middleClickRequirements(guard(options.middleClickRequirements(), input, validation))
            .build();
    }

    private static RequirementList guard(Optional<RequirementList> existing, AnvilInput input,
                                         AnvilValidation validation) {
        return new RequirementList(List.of()) {
            @Override
            public boolean evaluate(MenuHolder holder) {
                if (!validation.matches(input.submitted())) {
                    String message = holder.setPlaceholdersAndArguments(validation.errorMessage());
                    if (!message.isEmpty()) {
                        holder.getViewer().sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    }
                    return false;
                }
                if (existing.isEmpty() || existing.get().evaluate(holder)) return true;
                // DeluxeMenus evaluates the list-level deny handler separately. Preserve
                // it only for a native requirement failure, never for a regex failure.
                var deny = existing.get().getDenyHandler();
                if (deny != null) deny.onClick(holder);
                return false;
            }
        };
    }
}
