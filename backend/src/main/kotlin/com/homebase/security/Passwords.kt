package com.homebase.security

import at.favre.lib.crypto.bcrypt.BCrypt
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies

/**
 * Password hashing for HomeBase, backed by bcrypt.
 *
 * bcrypt is a salted, deliberately slow key-derivation function: every [hash] embeds a
 * random per-password salt and the work factor in its output, so equal passwords never
 * produce equal hashes (defeats rainbow tables / precomputation) and brute-forcing a
 * leaked hash costs ~2^cost bcrypt rounds per guess. [verify] re-derives with the salt
 * embedded in the stored string and compares in constant time.
 *
 * Replaces the earlier single-round, unsalted SHA-256, which had neither a salt nor a
 * work factor. Shared by login verification and user seeding so both stay consistent.
 */
object Passwords {
    // bcrypt work factor. 12 (~0.25 s/hash on current hardware) makes offline guessing
    // expensive while staying snappy for an interactive 2-user login. Tests lower it to
    // bcrypt's minimum via the `homebase.bcrypt.cost` system property so hashing doesn't
    // dominate the suite; production never sets it and gets 12.
    private val cost: Int =
        (System.getProperty("homebase.bcrypt.cost")?.toIntOrNull() ?: 12).coerceIn(4, 31)

    // bcrypt only consumes the first 72 bytes of a password. The SHA-512 strategy
    // pre-hashes longer inputs so a long passphrase neither silently truncates nor throws;
    // the same strategy must be applied when hashing and when verifying.
    private val longPasswords = LongPasswordStrategies.hashSha512(BCrypt.Version.VERSION_2A)
    private val hasher = BCrypt.with(longPasswords)
    private val verifier = BCrypt.verifyer(BCrypt.Version.VERSION_2A, longPasswords)

    /** Hashes [password] into a self-describing bcrypt string (version, cost, salt, digest). */
    fun hash(password: String): String =
        hasher.hashToString(cost, password.toCharArray())

    /** True iff [password] matches the bcrypt [stored] hash; false for any non-bcrypt string. */
    fun verify(password: String, stored: String): Boolean =
        verifier.verify(password.toCharArray(), stored.toCharArray()).verified
}
