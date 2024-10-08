package com.example.taskreminderapp

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.FileNotFoundException
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    //Static Properties and Methods Called from other classes by MainActivity.staticProp
    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        //TasksList acts like a global
        var tasksList = mutableListOf<Task>()
        val taskReminderNotificationTitle = "Reminded On:"

        // Boolean for newer API to check if notification is able to schedule
        @RequiresApi(Build.VERSION_CODES.S)
        fun canScheduleExactAlarms(context: Context): Boolean {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }

        // Method to schedule single notification with set content and exact date/time to notify
        fun scheduleNotification(
            context: Context,
            dateTimeLong: Long,
            title: String,
            message: String,
            notificationID: Int
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Check if the application can push notification else ask for permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!canScheduleExactAlarms(context)) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                    return
                }
            }

            // Passing down data to Notification.kt to build notification template
            val intent = Intent(context, Notification::class.java).apply {
                putExtra("title", title)
                putExtra("message", message)
                putExtra("notificationID", notificationID)
            }

            // Creating the notification into a pending intent
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                notificationID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Schedule the Notification as a pending intent to be executed on a specified time in the future
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    dateTimeLong,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                // Handle the exception (e.g., fallback to inexact alarm or notify the user)
                Log.e("AlarmScheduler", "Failed to schedule exact alarm", e)
                // Fallback to inexact alarm
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    dateTimeLong,
                    pendingIntent
                )
            }
        }
    }

    // Instance Properties and Methods
    private var selectionState = false
    private var sortMode = "descending"
    private lateinit var tasksRecyclerAdapter: TaskRecyclerAdapter

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private var calendar = Calendar.getInstance()
    private var currentYear = calendar.get(Calendar.YEAR)
    private var currentMonth = calendar.get(Calendar.MONTH)
    private var currentDay = calendar.get(Calendar.DAY_OF_MONTH)
    private var currentHour = calendar.get(Calendar.HOUR_OF_DAY)
    private var currentMinute = calendar.get(Calendar.MINUTE)

    private var newTaskContent = ""
    private var newTaskDate = ""
    private var newTaskTimeNotForDisplay = ""
    private var newTaskTime = ""

    private lateinit var currentEditingTask: Task

    private var editTaskContent = ""
    private var editTaskDate = ""
    private var editTaskTimeNotForDisplay = ""
    private var editTaskTime = ""

    // This method is used for initialising GUI and initialise state, fetch persistent data, add button functionality
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

//        fillDummyTasks() only used for testing/debugging RecyclerView
        onLaunchInitialise()
    }

    private fun onLaunchInitialise() {
        checkNotificationPermission()
        tasksList = initTasksDataPersistent().toMutableList()
        updateSubText()
        createDeleteButtonFunctionality()
        buildTaskRecyclerContainer(selectionState)
        createAddTaskFunctionality()
        createDropdownMenuFunctionality()
        startTaskReminderService()
    }

    // startTaskReminderService, stopTaskReminderService, and restartTaskReminderService() is used for
    // Running foreground service which is required to send notification locally via application background
    private fun startTaskReminderService() {
        val serviceIntent = Intent(this, TaskReminderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopTaskReminderService() {
        val serviceIntent = Intent(this, TaskReminderService::class.java)
        stopService(serviceIntent)
    }

    private fun restartTaskReminderService() {
        stopTaskReminderService()
        startTaskReminderService()
    }

    // This is used for enabling Create Tasks functionality
    private fun createAddTaskFunctionality() {
        // Add Task Button is on the bottom right of the UI which floats above the entire Task Reminder UI
        val addTaskButton = findViewById<FloatingActionButton>(R.id.btnAddTask)
        addTaskButton.setOnClickListener {
            resetNewTaskInputs()
            // Create and initialise a BottomSheetDialog which is a way to do an overlay drawer UI which appears above the current main UI
            val bottomSheetDialog = BottomSheetDialog(this)
            // BottomSheetDialog design for creating tasks is inflated from R.layout.add_new_task_modal
            val bottomSheetView = layoutInflater.inflate(R.layout.add_new_task_modal, null)

            bottomSheetDialog.setContentView(bottomSheetView)
            val newTaskEditText = bottomSheetView.findViewById<EditText>(R.id.editTextNewTask)
            // When the overlay appears setfocus onto EditText and show keyboard
            bottomSheetDialog.setOnShowListener {
                newTaskEditText.requestFocus()
                showKeyboard(newTaskEditText)
            }
            bottomSheetDialog.show()

            // Dismiss Overlay by clicking cancel
            val cancelButton = bottomSheetView.findViewById<Button>(R.id.btnCancelTask)
            cancelButton.setOnClickListener {
                bottomSheetDialog.dismiss()
            }

            // Enable capturing of date input and format into dd/MM/yyyy
            val setDateButton = bottomSheetView.findViewById<Button>(R.id.btnSetDate)
            setDateButton.setOnClickListener {
                val datePickerView = DatePickerDialog(
                    this,
                    { _, selectedYear, selectedMonth, selectedDay ->
                        updateCurrentTime()
                        val paddedDay = selectedDay.toString().padStart(2, '0')
                        val paddedMonth = (selectedMonth + 1).toString().padStart(2, '0')
                        newTaskDate = "$paddedDay/$paddedMonth/$selectedYear"
                        updateDateText(setDateButton, newTaskDate)
                    },
                    currentYear, currentMonth, currentDay
                )
                datePickerView.show()
            }

            // Enable capturing of time input and format into HH:mm:ss (For Display: hh:mm A.M/P.M)
            val setTimeButton = bottomSheetView.findViewById<Button>(R.id.btnSetTime)
            setTimeButton.setOnClickListener {
                val timePickerView = TimePickerDialog(
                    this,
                    {
                        _, selectedHour, selectedMinute ->
                        updateCurrentTime()
                        val amPm = if (selectedHour < 12) "A.M" else "P.M"
                        val hour = if (selectedHour > 12) selectedHour - 12 else if (selectedHour == 0) 12 else selectedHour
                        val paddedHour = hour.toString().padStart(2, '0')
                        val paddedSelectedHour = selectedHour.toString().padStart(2, '0')
                        val paddedMinute = selectedMinute.toString().padStart(2, '0')
                        newTaskTimeNotForDisplay = "$paddedSelectedHour:$paddedMinute:00"
                        newTaskTime = "${paddedHour}:${paddedMinute} $amPm"
                        updateTimeText(setTimeButton, newTaskTime)
                    },
                    currentHour, currentMinute, false
                )
                timePickerView.show()
            }

            // Check if all three inputs (task content, date and time) is valid and set before saving task onto the tasks list
            val saveButton = bottomSheetView.findViewById<Button>(R.id.btnCreateSave)
            saveButton.setOnClickListener {
                newTaskContent = newTaskEditText.text.toString()
                updateCurrentTime()
                // Check task content EditText if it is empty then show an alert view to warn the user
                if (newTaskContent == "") {
                    val alertView = AlertDialog.Builder(this)
                    alertView.setTitle("Task Content cannot be Empty!")
                    alertView.setMessage("Please fill in the task content input for this reminder before saving it.")
                    alertView.setPositiveButton(android.R.string.ok) { dialog, which ->
                        Toast.makeText(applicationContext,
                            android.R.string.ok, Toast.LENGTH_SHORT).show()
                    }
                    alertView.show()
                    return@setOnClickListener
                }
                // Check if the date and time are set before saving the task
                if (newTaskDate != "" || newTaskTime != "") {
                    val taskID = tasksList.size + 1
                    val dateTimeLongLocal = convertStringToMillis("$newTaskDate $newTaskTimeNotForDisplay")
                    val dateTimeLong = convertToUtcMillis(dateTimeLongLocal)
                    Log.d("Time", "$dateTimeLong | ${System.currentTimeMillis()}")
                    tasksList.add(Task(taskID, newTaskContent, newTaskDate, newTaskTime, dateFormat.format(System.currentTimeMillis()), dateTimeLong))
                    restartTaskReminderService()
                    saveTasksDataPersistent()
                    refreshTaskRecyclerContainer()
                    bottomSheetDialog.dismiss()
                    resetNewTaskInputs()
//                    Toast.makeText(this, )
                } else {
                    val alertView = AlertDialog.Builder(this)
                    alertView.setTitle("No Date or Time has been set!")
                    alertView.setMessage("Please set a date and time for this reminder before saving it.")
                    alertView.setPositiveButton(android.R.string.ok) { dialog, which ->
                        Toast.makeText(applicationContext,
                            android.R.string.ok, Toast.LENGTH_SHORT).show()
                    }
                    alertView.show()
                }
            }
        }
    }

    // This is used for enabling Edit Task Functionality (User clicks on a specific task CardView/task_row_card)
    private fun editCurrentTaskFunctionality() {
        if (currentEditingTask == null) return
        resetEditTaskInputs()
        val bottomSheetDialog = BottomSheetDialog(this)
        // BottomSheetView is inflated from R.layout.edit_existing_task_model
        val bottomSheetView = layoutInflater.inflate(R.layout.edit_existing_task_modal, null)

        bottomSheetDialog.setContentView(bottomSheetView)
        val editingTaskEditText = bottomSheetView.findViewById<EditText>(R.id.editTextExistingTask)
        bottomSheetDialog.setOnShowListener {
            editingTaskEditText.requestFocus()
            showKeyboard(editingTaskEditText)
        }
        bottomSheetDialog.show()

        // Set EditText placeholder as the specific task's content the user is currently editing on
        editingTaskEditText.setText(currentEditingTask.content)

        // Convert date string of the current editing task
        val dateString = currentEditingTask.date
        val (day, month, year) = dateString.split("/").map { it.toInt() }

        // Convert time string of the current editing task
        val timeString = currentEditingTask.time
        val timeParts = timeString.replace(".", "").split(":")
        val hourMinute = timeParts[0].toInt()
        val minute = timeParts[1].substring(0, 2).toInt()
        val period = timeParts[1].substring(3)


        val hour = when {
            period.uppercase() == "PM" && hourMinute != 12 -> hourMinute + 12
            period.uppercase() == "AM" && hourMinute == 12 -> 0
            else -> hourMinute
        }

        val cancelButton = bottomSheetView.findViewById<Button>(R.id.btnCancelEditTask)
        cancelButton.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        val setDateButton = bottomSheetView.findViewById<Button>(R.id.btnSetEditDate)
        editTaskDate = currentEditingTask.date
        setDateButton.setText(currentEditingTask.date)
        setDateButton.setOnClickListener {
            val datePickerView = DatePickerDialog(
                this,
                { _, selectedYear, selectedMonth, selectedDay ->
                    val paddedDay = selectedDay.toString().padStart(2, '0')
                    val paddedMonth = (selectedMonth + 1).toString().padStart(2, '0')
                    editTaskDate = "$paddedDay/$paddedMonth/$selectedYear"
                    updateDateText(setDateButton, editTaskDate)
                },
                year, month - 1, day
            )
            datePickerView.show()
        }

        val setTimeButton = bottomSheetView.findViewById<Button>(R.id.btnSetEditTime)
        editTaskTime = currentEditingTask.time
        editTaskTimeNotForDisplay = "${currentEditingTask.time.substring(0, 2)}:${currentEditingTask.time.substring(3, 5)}:00"
        setTimeButton.setText(currentEditingTask.time)
        setTimeButton.setOnClickListener {
            val timePickerView = TimePickerDialog(
                this,
                {
                        _, selectedHour, selectedMinute ->
                    val amPm = if (selectedHour < 12) "A.M" else "P.M"
                    val hour = if (selectedHour > 12) selectedHour - 12 else if (selectedHour == 0) 12 else selectedHour
                    val paddedHour = hour.toString().padStart(2, '0')
                    val paddedSelectedHour = selectedHour.toString().padStart(2, '0')
                    val paddedMinute = selectedMinute.toString().padStart(2, '0')
                    editTaskTimeNotForDisplay = "$paddedSelectedHour:$paddedMinute:00"
                    editTaskTime = "${paddedHour}:${paddedMinute} $amPm"
                    updateTimeText(setTimeButton, editTaskTime)
                },
                hour, minute, false
            )
            timePickerView.show()
        }

        // Instead of creating a new task to save it finds the current editing task by ID then edit its properties
        val saveButton = bottomSheetView.findViewById<Button>(R.id.btnCreateEdit)
        saveButton.setOnClickListener {
            editTaskContent = editingTaskEditText.text.toString()
            if (editTaskContent == "") {
                val alertView = AlertDialog.Builder(this)
                alertView.setTitle("Task Content cannot be Empty!")
                alertView.setMessage("Please fill in the task content input for this reminder before editing it.")
                alertView.setPositiveButton(android.R.string.ok) { dialog, which ->
                    Toast.makeText(applicationContext,
                        android.R.string.ok, Toast.LENGTH_SHORT).show()
                }
                alertView.show()
                return@setOnClickListener
            }
            if (editTaskDate != "" || editTaskTime != "") {
                val dateTimeLongLocal = convertStringToMillis("$editTaskDate $editTaskTimeNotForDisplay")
                val dateTimeLong = convertToUtcMillis(dateTimeLongLocal)
                tasksList.forEach {
                    task: Task ->
                    if (task.id == currentEditingTask.id) {
                        task.content = editTaskContent
                        task.date = editTaskDate
                        task.time = editTaskTime
                        task.due = dateTimeLong
                    }
                }
                restartTaskReminderService()
                saveTasksDataPersistent()
                refreshTaskRecyclerContainer()
                bottomSheetDialog.dismiss()
                resetEditTaskInputs()
            } else {
                val alertView = AlertDialog.Builder(this)
                alertView.setTitle("No Date or Time has been set!")
                alertView.setMessage("Please set a date and time for this reminder before editing it.")
                alertView.setPositiveButton(android.R.string.ok) { dialog, which ->
                    Toast.makeText(applicationContext,
                        android.R.string.ok, Toast.LENGTH_SHORT).show()
                }
                alertView.show()
            }
        }

    }

    // Reset inputs for creating new task
    private fun resetNewTaskInputs() {
        newTaskContent = ""
        newTaskTime = ""
        newTaskDate = ""
    }

    // Reset inputs for editing task
    private fun resetEditTaskInputs() {
        editTaskContent = ""
        editTaskDate = ""
        editTaskTime = ""
    }

    // Get current date/time used in Creating new task to set current date/time when user clicks on set date or time
    private fun updateCurrentTime() {
        calendar = Calendar.getInstance()
        currentYear = calendar.get(Calendar.YEAR)
        currentMonth = calendar.get(Calendar.MONTH)
        currentDay = calendar.get(Calendar.DAY_OF_MONTH)
        currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        currentMinute = calendar.get(Calendar.MINUTE)
    }

    private fun updateDateText(button: Button, date: String) {
        button.setText(date)
    }

    private fun updateTimeText(button: Button, time: String) {
        button.setText(time)
    }

    // RecyclerView is used for the UI tasks list acts as a container
    // which will allow for a continuous overflow of task by inflating task_row_card
    // RecyclerView is more efficient than ScrollView allowing for dynamic item content and saving scroll position on refresh
    private fun buildTaskRecyclerContainer(localSelectionState: Boolean) {
        // Sort list based on ascending/descending state of ID
        val sortedList = sortList()
        val tasksRecyclerContainer = findViewById<RecyclerView>(R.id.RecyclerTasksContainer)
        this.tasksRecyclerAdapter = TaskRecyclerAdapter(sortedList, localSelectionState,
            // On each Item when long click (holding) a specific item will enable selection mode
            // Making it easy for use to select the task they want to delete
            // On long click again will disable selection mode
            onItemLongClick = {
            task: Task ->
            selectionState = !selectionState
            if (selectionState) {
                task.selected = true
            } else {
                refreshSelectionTasksList()
            }
            updateSubText()
            refreshTaskRecyclerContainer()
        }, onItemClick = { // On Each Item Click will let user to edit the current clicked task unless the user is in selection mode
            task: Task ->
            if (selectionState) {
                task.selected = !task.selected
                refreshTaskRecyclerContainer()
                updateSubText()
            } else {
                currentEditingTask = task
                editCurrentTaskFunctionality()
            }
        })
        // Apply adapter and configure layout manager of RecyclerView
        tasksRecyclerContainer.adapter = tasksRecyclerAdapter
        tasksRecyclerContainer.layoutManager = LinearLayoutManager(this)
    }

    // This method is used for refreshing the RecyclerView. Called whenever taskslist changes or selectionState alternates (True/False)
    private fun refreshTaskRecyclerContainer() {
        updateSubText()
        val sortedList = sortList()
        if (tasksRecyclerAdapter != null) {
            tasksRecyclerAdapter.updateAllItems(sortedList, selectionState)
        }
    }

    // Enable task deletion on selection mode (Trash Can Icon on the top Right)
    private fun createDeleteButtonFunctionality() {
        val deleteButton = findViewById<ImageButton>(R.id.btnDeleteTasks)
        deleteButton.setOnClickListener {
            if (selectionState) {
                tasksList = tasksList.filter { task: Task ->
                    !task.selected
                }.toMutableList()
                restartTaskReminderService()
                saveTasksDataPersistent()
                refreshTaskRecyclerContainer()
                if (tasksList.size == 0) {
                    selectionState = false
                }
                updateSubText()
            }
        }
    }

    // Update subtext that show item count
    private fun updateSubText() {
        val subText = findViewById<TextView>(R.id.textViewTaskAmount)
        val deleteButton = findViewById<ImageButton>(R.id.btnDeleteTasks)
        if (!selectionState)  {
            deleteButton.setImageResource(R.drawable.delete_icon)
            subText.setTextColor(getColor(R.color.foreground))
            subText.setText("Showing ${tasksList.size} Tasks")
        } else {
            val currentlySelectedTasks = tasksList.filter {
                task ->
                task.selected
            }
            deleteButton.setImageResource(R.drawable.delete_icon_active)
            subText.setTextColor(getColor(R.color.accent_red))
            subText.setText("Currently Selecting ${currentlySelectedTasks.size} Tasks")
        }
    }

    // When the user exits the selection mode iterate and change all currently selected state tasks to false
    private fun refreshSelectionTasksList() {
        tasksList.forEach {
            task: Task ->
            task.selected = false
        }
    }

    // Show keyboard when overlay is used (Helper method)
    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    // Save Tasks persistently by utilising internal storage
    // Convert tasks list into a static json text when called
    private fun saveTasksDataPersistent() {
        val json = Gson().toJson(TasksListStructure(tasksList))
        openFileOutput("tasks.json", Context.MODE_PRIVATE).use {
            outputStream ->
            outputStream.write(json.toByteArray())
        }
    }

    // Fetch tasks list from the saved json file and initialise the tasksList variable
    private fun initTasksDataPersistent(): List<Task> {
        return try {
            val fileContents = openFileInput("tasks.json").bufferedReader().use { it.readText() }
            val taskListType = object : TypeToken<TasksListStructure>() {}.type
            Gson().fromJson<TasksListStructure>(fileContents, taskListType).tasks
        } catch (e: FileNotFoundException) {
            emptyList()
        }
    }

    // Helper Methods to convert date/time string to milliseconds in UTC Epoch (Using GMT+8)
    private fun convertStringToMillis(dateString: String): Long {
        val format = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        format.timeZone = TimeZone.getTimeZone("GMT+8")
        try {
            val date = format.parse(dateString)
            return date?.time ?: throw IllegalArgumentException("Invalid date string")
        } catch (e: Exception) {
            throw IllegalArgumentException("Error parsing date string", e)
        }
    }

    // Not needed (Plan to remove in the future redundant after refactor)
    private fun convertToUtcMillis(gmt8TimeInMillis: Long): Long {
        return gmt8TimeInMillis
    }

    // Helper Method to check if push notification is enabled on this application
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    "android.permission.POST_NOTIFICATIONS"
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission is granted, proceed with notification functionality
                }
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    "android.permission.POST_NOTIFICATIONS"
                ) -> {
                    // Show in-app explanation
                    showNotificationImportanceDialog()
                }
                else -> {
                    // Request the permission
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf("android.permission.POST_NOTIFICATIONS"),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                }
            }
        } else {
            // For Android versions below 13, notifications are enabled by default
            // You might want to check if notifications are enabled in app settings
            if (!areNotificationsEnabled()) {
                showNotificationImportanceDialog()
            }
        }
    }

    // Helper Method for Notification permission
    private fun areNotificationsEnabled(): Boolean {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.areNotificationsEnabled()
    }

    // If disable or deny, show a reason why the user should allow push notification
    private fun showNotificationImportanceDialog() {
        // Implement a dialog or in-app UI to explain why notifications are important
        // You can use AlertDialog, a custom dialog, or navigate to a specific fragment/activity
        AlertDialog.Builder(this)
            .setTitle("Enable Notifications")
            .setMessage("Notifications are important for task reminder to send notification")
            .setPositiveButton("Enable") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Not Now") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // Open settings to allowed denied users to allow push notification
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", packageName, null)
        startActivity(intent)
    }

    // Enabling Dropdown functionality for sorting tasks
    private fun createDropdownMenuFunctionality() {
        val dropdownButton = findViewById<ImageButton>(R.id.btnMenuDropdown)
        dropdownButton.setOnClickListener {
            view: View ->
            val popupMenu = PopupMenu(this, view)
            popupMenu.menuInflater.inflate(R.menu.dropdown_menu, popupMenu.menu)

            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.btnSortDescending -> {
                        sortMode = "descending"
                        refreshTaskRecyclerContainer()
                        true
                    }
                    R.id.btnSortAscending -> {
                        sortMode = "ascending"
                        refreshTaskRecyclerContainer()
                        true
                    }
                    else -> false
                }
            }
            popupMenu.show()
        }
    }

    private fun sortList(): MutableList<Task> {
        if (sortMode == "descending") {
            return tasksList.sortedByDescending { it.id }.toMutableList()
        }
        return tasksList.sortedBy { it.id }.toMutableList()
    }

    // Unused method on released only used for debugging
    // Was used to fill the application with dummy tasks to test RecyclerView functionality
    private fun fillDummyTasks() {
        val contents = listOf("Discuss Plans of World Domination with my War Secretary"
            , "Have tea with the Major General"
            , "Ensure the Resupply of Front Line Troops"
            , "Co-ordinate Structural Plans for a Ground Invasion"
            , "Declare War on the Trade Federation"
            , "Reassure Alliances with Vulnerable Countries"
            , "Execute Order 66"
            , "Declare an Emergency in the Senate!"
            , "Invade Coruscant with a Grand Army of Clones"
            , "Capture the Jedi Temple")
        val dates = listOf("12/08/2024"
            , "12/08/2024"
            , "15/08/2024"
            , "18/08/2024"
            , "20/08/2024"
            , "21/08/2024"
            , "30/08/2024"
            , "31/08/2024"
            , "05/09/2024"
            , "05/09/2024")
        val times = listOf("10:30 A.M"
            , "12:30 P.M"
            , "9:45 A.M"
            , "2:00 P.M"
            , "1:30 P.M"
            , "8:45 A.M"
            , "11:30 A.M"
            , "3:45 P.M"
            , "9:15 A.M"
            , "6:30 P.M")
        val currentDateTime = dateFormat.format(Date(System.currentTimeMillis()))
        contents.forEachIndexed {
            index, content ->
            val task = Task(index + 1, content, dates[index], times[index], currentDateTime, 0)
            tasksList.add(task)
        }
    }
}