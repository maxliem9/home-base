import { createElement, Fragment } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, it, expect } from 'vitest'
import { renderMarkdown, type MarkdownOptions } from './primitives'

// renderMarkdown returns ReactNode[]; render it to static HTML so we can assert on
// the output without a DOM. Guards the security-sensitive bits: URL allowlisting for
// links/images and the image:<id> → resolveImage routing (inline note images).
const html = (md: string, opts?: MarkdownOptions): string =>
  renderToStaticMarkup(createElement(Fragment, null, ...renderMarkdown(md, opts)))

describe('renderMarkdown — inline images & links', () => {
  it('renders an external http(s) image as a plain <img>', () => {
    const out = html('![cat](https://example.com/c.png)')
    expect(out).toContain('<img')
    expect(out).toContain('src="https://example.com/c.png"')
    expect(out).toContain('hb-md-img')
  })

  it('routes an image:<id> ref through resolveImage', () => {
    const out = html('![x](image:abc123)', {
      resolveImage: (id, alt) => createElement('figure', { 'data-id': id, 'data-alt': alt }),
    })
    expect(out).toContain('data-id="abc123"')
    expect(out).toContain('data-alt="x"')
  })

  it('falls back to alt text for an unresolved image:<id> ref', () => {
    const out = html('![only text](image:abc123)') // no resolver provided
    expect(out).not.toContain('<img')
    expect(out).toContain('only text')
  })

  it('renders a safe link with target and rel', () => {
    const out = html('[site](https://example.com)')
    expect(out).toContain('href="https://example.com"')
    expect(out).toContain('rel="noopener noreferrer"')
  })

  it('strips a javascript: link but keeps the text', () => {
    const out = html('[click me](javascript:alert(1))')
    expect(out).not.toContain('<a')
    expect(out.toLowerCase()).not.toContain('javascript:')
    expect(out).toContain('click me')
  })

  it('strips an unsafe image scheme, keeping the alt text', () => {
    const out = html('![danger](javascript:alert(1))')
    expect(out).not.toContain('<img')
    expect(out.toLowerCase()).not.toContain('javascript:')
    expect(out).toContain('danger')
  })

  it('strips a protocol-relative //host link (looks internal, navigates off-site)', () => {
    const out = html('[go](//evil.com/phish)')
    expect(out).not.toContain('<a')
    expect(out).not.toContain('evil.com')
    expect(out).toContain('go')
  })

  it('strips a case-variant JavaScript: link', () => {
    const out = html('[x](JavaScriPt:alert(1))')
    expect(out).not.toContain('<a')
    expect(out.toLowerCase()).not.toContain('javascript:')
  })

  it('degrades a data: image to its alt text', () => {
    const out = html('![pic](data:text/html,<script>alert(1)</script>)')
    expect(out).not.toContain('<img')
    expect(out).not.toContain('data:')
    expect(out).toContain('pic')
  })

  it('still renders basic inline formatting (regression)', () => {
    expect(html('**bold**')).toContain('<strong>bold</strong>')
  })
})
