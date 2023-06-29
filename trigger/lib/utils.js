import { readFile, writeFile } from 'fs/promises'

export const readJson = async (path) => {
  const data = await readFile(path, 'utf-8')
  const json = JSON.parse(data)

  return json
}

export const saveJson = async (path, string) => {
  const json = JSON.stringify(string, null, 2)
  await writeFile(path, json, 'utf-8')
}
