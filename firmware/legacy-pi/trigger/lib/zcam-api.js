import axios from 'axios'

export class ZCamApi {
  constructor(baseUrl) {
    this.baseUrl = baseUrl
    this.api = axios.create({
      baseURL: this.baseUrl,
    })
  }

  async acquireSession() {
    const res = await this.api.get('/ctrl/session')

    return res?.data
  }

  async shutdown() {
    const res = await this.api.get('/ctrl/shutdown')

    return res?.data
  }

  async mode(action) {
    const res = await this.api.get('/ctrl/mode', {
      params: {
        action,
      },
    })

    return res?.data
  }

  async recordStart() {
    const res = await this.rec('start')

    if (res?.code !== 0) {
      throw new Error(res?.msg)
    }

    return res
  }

  async recordStop() {
    const res = await this.rec('stop')

    if (res?.code !== 0) {
      throw new Error(res?.msg)
    }

    return res
  }

  async recordRemain() {
    const res = await this.rec('remain')

    if (res?.code !== 0) {
      throw new Error(res?.msg)
    }

    const minutes = parseFloat(res?.msg)

    return { minutes }
  }

  async rec(action) {
    const res = await this.api.get('/ctrl/rec', {
      params: { action },
    })

    return res?.data
  }
}
