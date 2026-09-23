# DigiCampus Import Test

An Android prototype for importing and processing student attendance data from the DigiCampus student portal.

This project was created as a technical prototype to understand and validate how attendance data can be safely imported from DigiCampus using an Android WebView.

---

## 🚀 Project Status

**Status: Working Prototype ✅**

The core DigiCampus attendance extraction functionality has been successfully implemented and tested on a physical Android device.

This repository serves as:

- A working prototype
- A backup of the DigiCampus integration
- A reference implementation for the future Attendance Manager application

---

## ✨ Features

### DigiCampus WebView

- Opens the official DigiCampus student portal inside the Android application.
- Allows the user to log in normally through the DigiCampus website.
- Maintains the WebView session so the user does not need to repeatedly log in.

### Main Attendance Import

The application can detect and extract the attendance summary from the main attendance page.

Example:

```text
Total Sessions: 344
Present: 261
Absent: 83
