import ms from 'ms'
import { readFile } from 'fs/promises'

export const sleep = (time) =>
  new Promise((resolve) => setTimeout(resolve, ms(time)))

export const log = (message, ...optionalParams) => {
  console.log(`${new Date().toISOString()} ${message}`, ...optionalParams)
}

export const readJson = async (path) => {
  const data = await readFile(path, 'utf-8')
  const json = JSON.parse(data)

  return json
}
