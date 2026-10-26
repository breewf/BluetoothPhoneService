package com.bluetooth.phone.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import com.bluetooth.phone.api.CallLogEntry
import com.bluetooth.phone.api.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读取系统通讯录/通话记录。
 *
 * 车机无 SIM 时，联系人通常由系统 Bluetooth app 的 PBAP Client 同步到 ContactsProvider；
 * 如果项目使用自研 PBAP 存储，只需替换这个 Repository，不影响 HFP/通话层。
 */
@SuppressLint("MissingPermission")
internal class ContactRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    /** 是否已授予读取联系人权限。 */
    fun canReadContacts(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** 是否已授予读取通话记录权限。 */
    fun canReadCallLog(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** 读取联系人（IO 线程），按号码去重，最多 [limit] 条。 */
    suspend fun contacts(limit: Int = 500): List<Contact> = withContext(Dispatchers.IO) {
        if (!canReadContacts()) return@withContext emptyList()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val result = mutableListOf<Contact>()
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                )
                val nameIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val photoIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI
                )
                val typeIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.TYPE
                )
                while (cursor.moveToNext() && result.size < limit) {
                    val number = cursor.getString(numberIndex) ?: continue
                    result += Contact(
                        id = cursor.getLong(idIndex),
                        name = cursor.getString(nameIndex),
                        number = number,
                        photoUri = cursor.getString(photoIndex),
                        type = cursor.getInt(typeIndex)
                    )
                }
            }
        }.onFailure {
            PhoneLog.contact.w(it) { "读取联系人失败" }
        }
        val distinct = result.distinctBy { it.number }
        PhoneLog.contact.d { "读取联系人: ${distinct.size} 条（上限 $limit）" }
        distinct
    }

    /** 读取最近通话记录（IO 线程），按时间倒序。 */
    suspend fun callLog(limit: Int = 200): List<CallLogEntry> = withContext(Dispatchers.IO) {
        if (!canReadCallLog()) return@withContext emptyList()

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val result = mutableListOf<CallLogEntry>()
        runCatching {
            resolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (cursor.moveToNext() && result.size < limit) {
                    result += CallLogEntry(
                        id = cursor.getLong(idIndex),
                        number = cursor.getString(numberIndex),
                        name = cursor.getString(nameIndex),
                        type = cursor.getInt(typeIndex),
                        dateMillis = cursor.getLong(dateIndex),
                        durationSeconds = cursor.getLong(durationIndex)
                    )
                }
            }
        }.onFailure {
            PhoneLog.contact.w(it) { "读取通话记录失败" }
        }
        PhoneLog.contact.d { "读取通话记录: ${result.size} 条（上限 $limit）" }
        result
    }
}
