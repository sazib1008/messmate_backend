package com.example.messmate_backend.service

import com.example.messmate_backend.dto.MenuItemDto
import com.example.messmate_backend.dto.WeeklyMenuDto
import com.example.messmate_backend.entity.Menu
import com.example.messmate_backend.entity.MenuItem
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.repository.DiningConfigurationRepository
import com.example.messmate_backend.repository.MealSessionConfigRepository
import com.example.messmate_backend.repository.MenuItemRepository
import com.example.messmate_backend.repository.MenuRepository
import com.example.messmate_backend.repository.MessRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneId

private val DHAKA_ZONE: ZoneId = ZoneId.of("Asia/Dhaka")

@Service
class MenuService(
    private val menuRepository: MenuRepository,
    private val menuItemRepository: MenuItemRepository,
    private val messRepository: MessRepository,
    private val diningConfigRepository: DiningConfigurationRepository,
    private val sessionConfigRepository: MealSessionConfigRepository
) {
    private val logger = LoggerFactory.getLogger(MenuService::class.java)

    fun getActiveSessionsForMess(messId: String): List<MealSession> {
        val diningConfigOpt = diningConfigRepository.findByMessId(messId)
        val sessionConfigs = diningConfigOpt.map {
            sessionConfigRepository.findAllByDiningConfigId(it.id)
        }.orElse(emptyList())

        return MealSession.entries.filter { session ->
            val cfg = sessionConfigs.find { it.session == session }
            cfg?.isEnabled ?: true
        }
    }

    @Transactional
    fun getOrCreateActiveMenu(messId: String): Menu? {
        val existing = menuRepository.findFirstByMessIdAndIsActiveTrue(messId)
        if (existing.isPresent) return existing.get()

        // Check if mess exists before saving to satisfy foreign key constraint
        return try {
            if (messRepository.existsById(messId)) {
                // Initialize clean, empty menu for new mess (zero dummy dishes)
                menuRepository.save(
                    Menu(
                        messId = messId,
                        title = "Weekly Culinary Schedule",
                        isActive = true,
                        effectiveFrom = LocalDate.now(DHAKA_ZONE)
                    )
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Could not create empty menu for mess $messId: ${e.message}")
            null
        }
    }

    @Transactional
    fun getActiveWeeklyMenu(messId: String): WeeklyMenuDto {
        val enabledSessions = getActiveSessionsForMess(messId)
        val menu = getOrCreateActiveMenu(messId)

        if (menu == null) {
            return WeeklyMenuDto(
                id = "empty-$messId",
                title = "Weekly Culinary Schedule",
                effectiveFrom = LocalDate.now(DHAKA_ZONE),
                items = emptyList(),
                activeSessions = enabledSessions
            )
        }

        // Defense-in-depth: Exclude menu items for sessions disabled in dining configuration
        val items = menuItemRepository.findAllByMenuIdOrderByDayOfWeekAsc(menu.id)
            .filter { it.session in enabledSessions }
            .map {
                MenuItemDto(
                    id = it.id,
                    dayOfWeek = it.dayOfWeek,
                    session = it.session,
                    itemName = it.itemName,
                    description = it.description,
                    category = it.category,
                    dietaryTags = it.dietaryTags
                )
            }

        return WeeklyMenuDto(
            id = menu.id,
            title = menu.title,
            effectiveFrom = menu.effectiveFrom,
            items = items,
            activeSessions = enabledSessions
        )
    }

    @Transactional
    fun getTodayMenu(messId: String): List<MenuItemDto> {
        val enabledSessions = getActiveSessionsForMess(messId)
        val menu = getOrCreateActiveMenu(messId) ?: return emptyList()

        // Java DayOfWeek: Monday=1 ... Sunday=7 -> map to Sunday=0 ... Saturday=6
        val dayOfWeek = LocalDate.now(DHAKA_ZONE).dayOfWeek.value % 7

        return menuItemRepository.findAllByMenuIdAndDayOfWeek(menu.id, dayOfWeek)
            .filter { it.session in enabledSessions }
            .map {
                MenuItemDto(
                    id = it.id,
                    dayOfWeek = it.dayOfWeek,
                    session = it.session,
                    itemName = it.itemName,
                    description = it.description,
                    category = it.category,
                    dietaryTags = it.dietaryTags
                )
            }
    }

    @Transactional
    fun createMenuItem(
        messId: String,
        dayOfWeek: Int,
        session: MealSession,
        itemName: String,
        description: String?,
        category: String?,
        dietaryTags: List<String>
    ): MenuItemDto {
        val enabledSessions = getActiveSessionsForMess(messId)
        if (session !in enabledSessions) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Cannot add dish: Meal session '$session' is disabled for this mess hall."
            )
        }

        val menu = getOrCreateActiveMenu(messId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Mess not found with ID: $messId")

        val newItem = MenuItem(
            menuId = menu.id,
            dayOfWeek = dayOfWeek,
            session = session,
            itemName = itemName,
            description = description,
            category = category ?: "Main",
            dietaryTags = dietaryTags
        )
        val saved = menuItemRepository.save(newItem)
        return MenuItemDto(
            id = saved.id,
            dayOfWeek = saved.dayOfWeek,
            session = saved.session,
            itemName = saved.itemName,
            description = saved.description,
            category = saved.category,
            dietaryTags = saved.dietaryTags
        )
    }

    @Transactional
    fun deleteMenuItem(itemId: String) {
        if (!menuItemRepository.existsById(itemId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Menu item not found")
        }
        menuItemRepository.deleteById(itemId)
    }
}
