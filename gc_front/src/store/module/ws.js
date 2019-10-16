import Vue from 'vue'
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
      state.alarms.slice(0, state.alarms.length)
    },
    SOCKET_ONOPEN(state, event) {
      Vue.prototype.$socket = event.currentTarget
      state.socket.isConnected = true
    },
    SOCKET_ONCLOSE(state, event) {
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
    },
    ReportAlarm(state, event) {
      state.alarms.splice(0, state.alarms.length)
      for (let ar of event.alarms) {
        state.alarms.push(ar)
      }
    }
  },
  getters: {
    unreadAlarm: state => state.alarms,
    unreadAlarmCount: state => state.alarms.length
  },
  actions: {
    readAlarm(context) {
      try {
        return new Promise((resolve, reject) => {
          context.commit('clearAlarm')
          Vue.prototype.$socket.sendObj({ msgType: 'alarmRead' })
          resolve();
        });
      } catch (error) {
        reject(error);
      }
    }
  }
}
