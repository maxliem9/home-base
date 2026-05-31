package com.homebase.security

import java.security.MessageDigest

/**
 * Hashes a password with SHA-256 and returns a lowercase hex string.
 * Shared by login verification and user seeding so both produce identical hashes.
 */
fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
