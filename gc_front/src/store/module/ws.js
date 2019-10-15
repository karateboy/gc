
export default {
  state: {
    socket: {
      isConnected: false,
      message: '',
      reconnectError: false
    },
    alarms: []
  },
  mutations: {
    clearAlarm(state) {
      state.hasAlarm = false;
    },
    SOCKET_ONOPEN(state, event) {
      console.log('SOCKET_ONOPEN')
      state.socket.isConnected = true
    },
    SOCKET_ONCLOSE(state, event) {
      console.log('SOCKET_ONCLOSE')
      state.socket.isConnected = false
    },
    SOCKET_ONERROR(state, event) {
      console.error(state, event)
    },
    // default handler called for all methods
    SOCKET_ONMESSAGE(state, message) {
      state.socket.message = message
    },
    // mutations for reconnect methods
    SOCKET_RECONNECT(state, count) {
      console.info(state, count)
    },
    SOCKET_RECONNECT_ERROR(state) {
      state.socket.reconnectError = true;
    }
  },
  getters: {
    alarmUnreadCount: state => state.messageUnreadList.length
  },
  actions: {
    getMessageList({ state, commit }) {
      return new Promise((resolve, reject) => {
        resolve()
      }).catch(error => {
        reject(error)
      })
    }
  }
}
