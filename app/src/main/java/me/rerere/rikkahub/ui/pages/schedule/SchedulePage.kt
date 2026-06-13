package me.rerere.rikkahub.ui.pages.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.ext.plus
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone
import kotlin.uuid.Uuid

/**
 * Minimal create / list / delete UI for one conversation's task schedules (SPEC.md M5 / task T10).
 * Every mutation flows through [ScheduleVM], which WRITES through the same [TaskScheduleRepository]
 * the schedule tools use — there is no UI-only legality path. Strings are English-only placeholders
 * (CLAUDE.md i18n rule: no localization unless explicitly requested).
 */
@Composable
fun SchedulePage(
    targetAssistantId: Uuid,
    conversationId: Uuid? = null,
    vm: ScheduleVM = koinViewModel { parametersOf(targetAssistantId, conversationId) },
) {
    val toaster = LocalToaster.current
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var createError by remember { mutableStateOf<CreateScheduleError?>(null) }
    var deleteTarget by remember { mutableStateOf<ScheduleSnapshot?>(null) }

    LaunchedEffect(vm) { vm.load() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Scheduled tasks") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Lucide.Plus, contentDescription = "Add schedule")
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (schedules.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Lucide.CalendarClock,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "No scheduled tasks yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(schedules, key = { it.id.toString() }) { snapshot ->
                ScheduleCard(
                    snapshot = snapshot,
                    onDelete = { deleteTarget = snapshot },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateScheduleDialog(
            error = createError,
            onDismiss = {
                showCreateDialog = false
                createError = null
            },
            // The dialog is NOT dismissed here: a create can be Rejected (over-length prompt, sub-floor
            // interval, bad zone, cap breach), and closing before the result is known destroys the user's
            // input. The dialog stays open until the result arrives — only an Accepted dismisses it; a
            // Rejected keeps it open and surfaces the reason inline (M2 / task T3). Rejection is an
            // EXPECTED domain outcome, so it is shown in the dialog, never toasted.
            onConfirm = { draft ->
                createError = null
                vm.create(draft) { result ->
                    when (result) {
                        is ScheduleMutationResult.Accepted -> {
                            showCreateDialog = false
                            toaster.show("Schedule created")
                        }

                        is ScheduleMutationResult.Rejected ->
                            createError = createScheduleError(result.reason)
                    }
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = "Delete schedule",
        confirmText = "Delete",
        dismissText = "Cancel",
        onConfirm = {
            deleteTarget?.let { snapshot ->
                vm.delete(snapshot.id) { result ->
                    if (result is ScheduleMutationResult.Rejected) toaster.show(result.reason)
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text("This scheduled task will be removed and will not fire again.")
    }
}

@Composable
private fun ScheduleCard(
    snapshot: ScheduleSnapshot,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.CalendarClock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = snapshot.prompt.ifBlank { "(empty prompt)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = scheduleSubtitle(snapshot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Lucide.Trash2,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun scheduleSubtitle(snapshot: ScheduleSnapshot): String {
    val kindLabel = when (snapshot.kind) {
        ScheduleKind.ONE_SHOT -> "One-shot"
        ScheduleKind.RECURRING -> "Recurring"
    }
    val state = if (snapshot.enabled) "enabled" else "disabled"
    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply {
        timeZone = runCatching { TimeZone.getTimeZone(snapshot.timeZoneId) }.getOrDefault(TimeZone.getDefault())
    }
    return "$kindLabel · next ${df.format(Date(snapshot.nextFireAt))} · $state"
}

/**
 * Production create form (SPEC.md M3 / task T8). The dialog OWNS its field state via
 * [rememberSaveable] primitives and projects them into a pure [ScheduleFormState] for the live
 * [scheduleSummary] preview and the M4 submit gate — the VM stays the legality conduit, the dialog
 * decides display (see the [onConfirm] comment at the call site).
 *
 * Controls, each grounded in the data model:
 *  - segmented kind (One-shot / Recurring) and unit (minutes / hours / days);
 *  - a -/+ stepper whose floor is [minEveryFor] for the chosen unit, so it can never produce a
 *    sub-floor draft the repository rejects (and re-clamps `every` up when the unit switches to a
 *    coarser floor, e.g. HOURS→MINUTES forces `every` to at least 15);
 *  - an M3 date + time picker for `firstFireAt` (replaces the old blind `now + 1h`);
 *  - an M3 time picker for the daily `timeOfDay`, shown only for DAYS (Recurrence ignores it
 *    otherwise — Recurrence.kt:34);
 *  - an editable, searchable IANA timezone picker defaulting to the device zone;
 *  - a LIVE summary line computed by [scheduleSummary] — the same `Recurrence.nextOccurrenceAfter`
 *    the worker fires, so the preview equals reality (SC4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScheduleDialog(
    error: CreateScheduleError?,
    onDismiss: () -> Unit,
    onConfirm: (ScheduleDraft) -> Unit,
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ScheduleKind.ONE_SHOT) }
    var unit by rememberSaveable { mutableStateOf(RecurrenceUnit.HOURS) }
    var every by rememberSaveable { mutableIntStateOf(1) }
    // Seed first fire one hour out so the user edits a future, valid instant, not a past one.
    var firstFireAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis() + 60 * 60 * 1000) }
    var timeOfDay by rememberSaveable { mutableStateOf<String?>(null) }
    var timeZoneId by rememberSaveable { mutableStateOf(TimeZone.getDefault().id) }

    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showTimeOfDayPicker by rememberSaveable { mutableStateOf(false) }
    var showTimeZonePicker by rememberSaveable { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val formState = ScheduleFormState(
        prompt = prompt,
        kind = kind,
        every = every,
        unit = unit,
        firstFireAt = firstFireAt,
        timeOfDay = timeOfDay.takeIf { kind == ScheduleKind.RECURRING && unit == RecurrenceUnit.DAYS },
        timeZoneId = timeZoneId,
    )
    val validationErrors = formState.validate(now)
    val previewSpec = if (kind == ScheduleKind.RECURRING) {
        RecurrenceSpec(every = every, unit = unit, timeOfDay = formState.timeOfDay)
    } else null
    val summary = runCatching {
        scheduleSummary(
            kind = kind,
            spec = previewSpec,
            firstFireAt = firstFireAt,
            timeZoneId = timeZoneId,
            now = now,
        )
    }.getOrNull()

    val displayZone = runCatching { ZoneId.of(timeZoneId) }.getOrDefault(ZoneId.systemDefault())
    val fireDateTimeLabel = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply {
        timeZone = TimeZone.getTimeZone(displayZone)
    }.format(Date(firstFireAt))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New scheduled task") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompt") },
                    minLines = 3,
                    maxLines = 6,
                    isError = error?.field == CreateScheduleField.PROMPT ||
                        validationErrors.containsKey(ScheduleField.PROMPT),
                    supportingText = fieldSupportingText(
                        repositoryError = error?.takeIf { it.field == CreateScheduleField.PROMPT }?.message,
                        validationError = validationErrors[ScheduleField.PROMPT],
                    ),
                )

                FormItem(label = { Text("Repeat") }) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val kinds = ScheduleKind.entries
                        kinds.forEachIndexed { index, candidate ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                                selected = kind == candidate,
                                onClick = { kind = candidate },
                            ) {
                                Text(if (candidate == ScheduleKind.ONE_SHOT) "One-shot" else "Recurring")
                            }
                        }
                    }
                }

                if (kind == ScheduleKind.RECURRING) {
                    FormItem(label = { Text("Unit") }) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val units = RecurrenceUnit.entries
                            units.forEachIndexed { index, candidate ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, units.size),
                                    selected = unit == candidate,
                                    onClick = {
                                        unit = candidate
                                        // Re-clamp up so switching to a coarser floor (e.g. → MINUTES,
                                        // floor 15) can never leave `every` sitting below the new floor.
                                        every = maxOf(every, minEveryFor(candidate))
                                    },
                                ) {
                                    Text(candidate.name.lowercase())
                                }
                            }
                        }
                    }

                    FormItem(
                        label = { Text("Every") },
                        description = {
                            val floor = minEveryFor(unit)
                            if (floor > 1) Text("Minimum $floor for ${unit.name.lowercase()}")
                        },
                    ) {
                        Stepper(
                            value = every,
                            min = minEveryFor(unit),
                            onValueChange = { every = it },
                            isError = error?.field == CreateScheduleField.EVERY ||
                                validationErrors.containsKey(ScheduleField.EVERY),
                        )
                        validationErrors[ScheduleField.EVERY]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    if (unit == RecurrenceUnit.DAYS) {
                        FormItem(
                            label = { Text("Time of day") },
                            description = { Text("Optional — fires at this local time") },
                            tail = {
                                OutlinedButton(onClick = { showTimeOfDayPicker = true }) {
                                    Text(timeOfDay ?: "Anchor time")
                                }
                            },
                        )
                    }
                }

                FormItem(
                    label = { Text(if (kind == ScheduleKind.ONE_SHOT) "Fire at" else "First fire") },
                    tail = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showDatePicker = true }) { Text("Date") }
                            OutlinedButton(onClick = { showTimePicker = true }) { Text("Time") }
                        }
                    },
                ) {
                    Text(fireDateTimeLabel, style = MaterialTheme.typography.bodyMedium)
                }

                FormItem(
                    label = { Text("Timezone") },
                    tail = {
                        OutlinedButton(onClick = { showTimeZonePicker = true }) { Text("Change") }
                    },
                ) {
                    Text(
                        text = timeZoneId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (validationErrors.containsKey(ScheduleField.TIMEZONE)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                summary?.let {
                    HorizontalDivider()
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Dialog-level reasons (cap breach, unrecognized) and any repository timezone reason
                // surface here so no rejection is silently dropped (field-mapped reasons render inline above).
                error
                    ?.takeIf { it.field == CreateScheduleField.NONE || it.field == CreateScheduleField.TIMEZONE }
                    ?.let {
                        Text(
                            text = it.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validationErrors.isEmpty(),
                onClick = {
                    val spec = previewSpec?.let { Json.encodeToString(it) }
                    onConfirm(
                        ScheduleDraft(
                            targetAssistantId = Uuid.NIL, // bound by the VM to the screen's target assistant
                            prompt = prompt.trim(),
                            kind = kind,
                            firstFireAt = firstFireAt,
                            timeZoneId = timeZoneId,
                            recurrenceSpec = spec,
                        )
                    )
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(firstFireAt).atZone(displayZone)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { utcMidnight ->
                        // DatePicker reports UTC-midnight of the picked day; recombine that calendar date
                        // with the existing local clock time in the schedule's zone.
                        val pickedDate = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
                        firstFireAt = combineDateTime(pickedDate, currentLocalTime(firstFireAt, displayZone), displayZone)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val localTime = currentLocalTime(firstFireAt, displayZone)
        val timePickerState = rememberTimePickerState(
            initialHour = localTime.hour, initialMinute = localTime.minute, is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val pickedDate = Instant.ofEpochMilli(firstFireAt).atZone(displayZone).toLocalDate()
                    firstFireAt = combineDateTime(
                        pickedDate, LocalTime.of(timePickerState.hour, timePickerState.minute), displayZone,
                    )
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = timePickerState) },
        )
    }

    if (showTimeOfDayPicker) {
        val seed = timeOfDay?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9, 0)
        val timePickerState = rememberTimePickerState(
            initialHour = seed.hour, initialMinute = seed.minute, is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimeOfDayPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    timeOfDay = "%02d:%02d".format(timePickerState.hour, timePickerState.minute)
                    showTimeOfDayPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimeOfDayPicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = timePickerState) },
        )
    }

    if (showTimeZonePicker) {
        TimeZonePickerDialog(
            current = timeZoneId,
            onPick = { timeZoneId = it; showTimeZonePicker = false },
            onDismiss = { showTimeZonePicker = false },
        )
    }
}

/** -/+ stepper bound at [min] (the unit's legal floor) so it can never emit a sub-floor value. */
@Composable
private fun Stepper(
    value: Int,
    min: Int,
    onValueChange: (Int) -> Unit,
    isError: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onValueChange(maxOf(min, value - 1)) },
            enabled = value > min,
        ) {
            Icon(Lucide.Minus, contentDescription = "Decrease")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = { onValueChange(value + 1) }) {
            Icon(Lucide.Plus, contentDescription = "Increase")
        }
    }
}

/**
 * Searchable IANA timezone picker (SPEC.md M3 / task T8, Assumption 8). The full
 * [ZoneId.getAvailableZoneIds] list is offered with a free-text filter so no zone the user needs is
 * hidden behind curation; the current selection is preselected.
 */
@Composable
private fun TimeZonePickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val allZones = remember { ZoneId.getAvailableZoneIds().sorted() }
    val filtered = remember(query) {
        if (query.isBlank()) allZones else allZones.filter { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timezone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search") },
                    singleLine = true,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it }) { zone ->
                        TextButton(
                            onClick = { onPick(zone) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = zone,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (zone == current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A red `supportingText` slot preferring the repository's verbatim reason, else the mirrored guard. */
private fun fieldSupportingText(
    repositoryError: String?,
    validationError: String?,
): (@Composable () -> Unit)? {
    val message = repositoryError ?: validationError ?: return null
    return { Text(message, color = MaterialTheme.colorScheme.error) }
}

/** The local wall-clock time of [millis] in [zone] — the time part the date picker must preserve. */
private fun currentLocalTime(millis: Long, zone: ZoneId): LocalTime =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()

/** Recombine a calendar [date] and wall [time] into an epoch-millis instant in [zone]. */
private fun combineDateTime(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
    ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
