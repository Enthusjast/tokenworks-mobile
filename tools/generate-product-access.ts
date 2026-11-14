import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { productAccessOutputs } from './product-access-contract.js'
import { checkTypertContracts } from './typert-contract-check.js'

if (process.argv.slice(2).some(argument => argument !== '--check')) throw new Error('Usage: tsx tools/generate-product-access.ts [--check]')
const outputs = await productAccessOutputs()
if (process.argv.includes('--check')) {
  await checkTypertContracts(outputs, fileURLToPath(new URL('../', import.meta.url)))
} else {
  for (const { path, content } of outputs) {
    await mkdir(new URL('../' + path.substring(0, path.lastIndexOf('/')) + '/', import.meta.url), { recursive: true })
    await writeFile(new URL('../' + path, import.meta.url), content)
  }
}
console.log(`${process.argv.includes('--check') ? 'Checked' : 'Generated'} canonical native product access policy`)
