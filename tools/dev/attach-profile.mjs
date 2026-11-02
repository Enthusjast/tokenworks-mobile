/** Persist the local fixture into an existing linked Web profile, preserving other rows. */
import { readFile, writeFile, rename, realpath, mkdir } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'
import YAML from 'yaml'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const profile = process.argv[2]
if (!profile || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$/.test(profile)) throw new Error('invalid profile')
const directory = resolve(process.env.DSH_HOME || resolve(homedir(), '.dsh'), 'profiles', profile)
const installed = await realpath(resolve(directory, 'node_modules/@ustc/dsh-remote-control'))
if (installed !== resolve(root, 'host/remote-control')) throw new Error('profile must link this checkout before attaching')
const path = resolve(directory, 'cordis.patch.yml')
const original = await readFile(path, 'utf8')
const document = YAML.parseDocument(original)
if (document.errors.length || !YAML.isSeq(document.contents)) throw new Error('profile patch must be a valid YAML array')
const overlay = JSON.parse(await readFile(resolve(root, '.local/dev/dsh.patch.yml'), 'utf8'))
const hmr = overlay[0]
const plugin = overlay[1].insert[0]
plugin.name = resolve(root, 'host/remote-control/development.mjs')
hmr.config.root.push('host/remote-control/client', 'host/remote-control/development.mjs')
const entries = document.contents.items
const existing = entries.find(row => YAML.isMap(row) && row.get('id') === 'hmr')
if (existing) {
  // Merge watch roots/ignored rules; retain existing watcher configuration.
  const old = existing.get('config')?.toJSON?.() || {}
  existing.set('disabled', false)
  existing.set('config', document.createNode({ ...hmr.config, ...old, base: hmr.config.base,
    root: [...new Set([...(old.root || []).map(value => old.base ? resolve(fileURLToPath(new URL('.', old.base)), value) : resolve(directory, value)), ...hmr.config.root.map(value => resolve(root, value))])],
    ignored: [...new Set([...(old.ignored || []), ...hmr.config.ignored])],
  }))
} else document.add({ ...hmr, disabled: false })
let found = false
for (const row of entries) {
  if (!YAML.isMap(row)) continue
  if (row.get('id') === plugin.id) { row.set('name', plugin.name); row.set('config', document.createNode(plugin.config)); found = true }
  const insert = row.get('insert')
  if (YAML.isSeq(insert)) for (const child of insert.items) if (YAML.isMap(child) && child.get('id') === plugin.id) {
    child.set('name', plugin.name); child.set('config', document.createNode(plugin.config)); found = true
  }
}
if (!found) document.add({ insert: [plugin] })
const backup = resolve(root, '.local/dev/profile-backups')
await mkdir(backup, { recursive: true, mode: 0o700 })
await writeFile(resolve(backup, `${profile}-${randomUUID()}.yml`), original, { flag: 'wx', mode: 0o600 })
const temporary = path + '.' + randomUUID() + '.tmp'
await writeFile(temporary, document.toString(), { flag: 'wx', mode: 0o600 })
if (await readFile(path, 'utf8') !== original) throw new Error('profile changed while preparing attachment; retry explicitly')
await rename(temporary, path)
console.log('existing profile updated; original saved privately; DSH will recompose through HMR')
