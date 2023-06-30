import { readFile, writeFile } from 'fs/promises'
import { customAlphabet } from 'nanoid'
import _ from 'lodash'

const nanoid = customAlphabet('1234567890abcdef', 6)

export const readJson = async (path) => {
  const data = await readFile(path, 'utf-8')
  const json = JSON.parse(data)

  // generate a deviceId if it hasn't already been generated
  if (!json.deviceId) {
    json.deviceId = nanoid().toUpperCase()
    await saveJson(path, json)
  }

  json.deviceName = json.name ? `${json.name}-${json.deviceId}` : json.deviceId

  return json
}

export const saveJson = async (path, json) => {
  const clonedJson = _.cloneDeep(json)
  delete clonedJson.deviceName

  const string = JSON.stringify(clonedJson, null, 2)
  await writeFile(path, string, 'utf-8')
}
