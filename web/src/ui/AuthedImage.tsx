import { useEffect, useState, type ImgHTMLAttributes } from 'react'
import { authFetch } from '../api'

// Loads a protected image through authFetch (Authorization header) into a blob URL, so the JWT
// never rides in the image URL. The object URL is revoked on unmount / when the target changes.
// Shared by the note and recipe galleries — pass the image endpoint URL (noteImageUrl /
// recipeImageUrl from api.ts). Renders nothing while loading or if the fetch fails/forbidden.
export function AuthedImage({ url, token, ...imgProps }: {
  url: string
  token: string
} & ImgHTMLAttributes<HTMLImageElement>) {
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => {
    let active = true
    let objectUrl: string | null = null
    // Clear any previous blob before loading a new target, so a prop change in place
    // never renders the just-revoked object URL for a frame.
    setSrc(null)
    authFetch(token, url)
      .then((res) => (res.ok ? res.blob() : Promise.reject(new Error(String(res.status)))))
      .then((blob) => {
        if (!active) return
        objectUrl = URL.createObjectURL(blob)
        setSrc(objectUrl)
      })
      .catch(() => { /* broken/forbidden image → render nothing */ })
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [url, token])
  return src ? <img src={src} {...imgProps} /> : null
}
