import Vue from 'vue'
import Vuex from 'vuex'

import user from './module/user'
import app from './module/app'
import ws from './module/ws'
import axios from "axios"
Vue.use(Vuex)

export default new Vuex.Store({
  state: {
    localMode: true
  },
  mutations: {
    setLocalMode(state, mode) {
      state.localMode = mode
    }
  },
  actions: {
    fetchOperationMode(context) {
      axios.get("/operationMode").then(res => {
        const ret = res.data;
        this.commit("setLocalMode", ret.mode === 0)
      })
    },
    setOperationMode(context, payload) {
      let mode = 0

      if (payload === false) {
        mode = 1
      }

      this.commit("setLocalMode", payload)
      axios.put("/operationMode",
        {
          mode
        }
      ).then(res => {
        this.commit("setLocalMode", payload)
      })
    }
  },
  modules: {
    user,
    app,
    ws
  }
})
