// 128-bit UUIDs for the Camera Trigger BLE service.
// bleno wants the no-dash, lowercase form. The "pretty" forms (with dashes)
// are what most BLE clients (Capacitor, web, nRF Connect, ...) display.

export const SERVICE_UUID = 'b87f00019c4b4e6f9a8e1a1b2c3d4e5f'
export const RX_UUID = 'b87f00029c4b4e6f9a8e1a1b2c3d4e5f' // client -> device (write)
export const TX_UUID = 'b87f00039c4b4e6f9a8e1a1b2c3d4e5f' // device -> client (notify)

export const SERVICE_UUID_PRETTY = 'b87f0001-9c4b-4e6f-9a8e-1a1b2c3d4e5f'
export const RX_UUID_PRETTY = 'b87f0002-9c4b-4e6f-9a8e-1a1b2c3d4e5f'
export const TX_UUID_PRETTY = 'b87f0003-9c4b-4e6f-9a8e-1a1b2c3d4e5f'
