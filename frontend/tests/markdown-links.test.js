import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { mdToHtml as webMdToHtml } from '../src/utils.js'

const mobileSource = await readFile(new URL('../../mobile/utils/md.js', import.meta.url), 'utf8')
const { mdToHtml: mobileMdToHtml } = await import('data:text/javascript;base64,' + Buffer.from(mobileSource).toString('base64'))

for (const [name, mdToHtml] of [['web', webMdToHtml], ['mobile', mobileMdToHtml]]) {
  test(`${name}: renders external citations with safe attributes`, () => {
    const html = mdToHtml('[官方资料](https://example.com/search?a=1&b=2)')
    assert.equal(html, '<p><a href="https://example.com/search?a=1&amp;b=2" target="_blank" rel="noopener noreferrer">官方资料</a></p>')
    assert.match(mdToHtml('[百科](https://example.com/wiki/C_(programming_language))'), /href="https:\/\/example\.com\/wiki\/C_\(programming_language\)"/)
    assert.match(mdToHtml('| 来源 |\n| --- |\n| [文章](https://example.com/) |'), /<a href="https:\/\/example\.com\//)
  })

  test(`${name}: rejects unsafe links and keeps code examples literal`, () => {
    assert.doesNotMatch(mdToHtml('[攻击](javascript:alert(1))'), /<a /)
    assert.doesNotMatch(mdToHtml('[攻击](https://example.com/"onload=bad)'), /<a /)
    assert.match(mdToHtml('<script>alert(1)</script>'), /&lt;script&gt;/)
    assert.doesNotMatch(mdToHtml('`[示例](https://example.com/)`'), /<a /)
    assert.doesNotMatch(mdToHtml('```\n[示例](https://example.com/)\n```'), /<a /)
  })
}
