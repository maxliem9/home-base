package com.homebase.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.date

object UsersTable : Table("users") {
    val id = uuid("id")
    val username = varchar("username", 50)
    val passwordHash = text("password_hash")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object TodosTable : Table("todos") {
    val id = uuid("id")
    val title = text("title")
    val description = text("description").nullable()
    val status = varchar("status", 20)
    val assignee = varchar("assignee", 50).nullable()
    val dueDate = date("due_date").nullable()
    val priority = varchar("priority", 10).nullable()
    val createdBy = varchar("created_by", 50)
    val createdAt = timestamp("created_at")
    val doneAt = timestamp("done_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
