import { useEffect, useState } from 'react'
import { API_BASE, safeFetch } from '../api'
import { User } from '../types'
import { HOUSEHOLD_USERS } from '../ui/format'

// The household members' usernames, read once from GET /users (the seeded users,
// the same endpoint the shared-timer feature uses). Falls back to the known seed
// users so the assignee chips still render in local dev without a backend
// — mirrors App.tsx's householdName fallback.
export function useHouseholdUsers(token: string): string[] {
  const [users, setUsers] = useState<string[]>(HOUSEHOLD_USERS)
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      const result = await safeFetch(token, `${API_BASE}/users`)
      if (!result.ok || cancelled || !result.res.ok) return
      try {
        const data: User[] = await result.res.json()
        const names = Array.isArray(data) ? data.map((u) => u.username).filter(Boolean) : []
        if (!cancelled && names.length) setUsers(names)
      } catch {
        // keep the fallback on a malformed body
      }
    })()
    return () => {
      cancelled = true
    }
  }, [token])
  return users
}
