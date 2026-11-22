package com.paragrudani.replyly.history.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.ZoneId

/** Migrations of [HistoryDatabase]. Each one is covered by `HistoryMigrationTest`. */
object HistoryMigrations {
    /**
     * 1 to 2 (issue #26): message dates and replies to me. Every existing row gets the local date
     * (in [zone]) of its `first_seen_at`, marked approx: no row of version 1 knows its real date.
     */
    fun from1To2(zone: ZoneId): Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN message_date INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN date_is_approx INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN replies_to_me INTEGER NOT NULL DEFAULT 0")
                // In Kotlin, not SQL: SQLite's 'localtime' follows the C library's zone, not [zone].
                val firstSeen = mutableListOf<Pair<Long, Long>>()
                db.query("SELECT id, first_seen_at FROM messages").use { cursor ->
                    while (cursor.moveToNext()) firstSeen += cursor.getLong(0) to cursor.getLong(1)
                }
                val sql = "UPDATE messages SET message_date = ?, date_is_approx = 1 WHERE id = ?"
                db.compileStatement(sql).use { update ->
                    for ((id, millis) in firstSeen) {
                        update.bindLong(
                            1,
                            Instant
                                .ofEpochMilli(millis)
                                .atZone(zone)
                                .toLocalDate()
                                .toEpochDay(),
                        )
                        update.bindLong(2, id)
                        update.executeUpdateDelete()
                        update.clearBindings()
                    }
                }
            }
        }

    /** Every migration, for the DB builder. */
    fun all(zone: ZoneId): Array<Migration> = arrayOf(from1To2(zone))
}
