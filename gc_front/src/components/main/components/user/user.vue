/* eslint-disable handle-callback-err */
<template>
  <div class="user-avatar-dropdown">
    <Dropdown @on-click="handleClick">
      <Badge :count="unreadAlarm.length" :offset="[15, 0]" :text="badgeText">
        <Avatar :src="userAvatar" />
      </Badge>
      <Icon :size="18" type="md-arrow-dropdown"></Icon>
      <DropdownMenu slot="list">
        <DropdownItem name="readAlarm" v-for="alarm in unreadAlarm" :key="alarm._id">{{alarm.desc}}</DropdownItem>
        <DropdownItem name="readAlarm">檢查警報</DropdownItem>
        <DropdownItem name="logout">登出</DropdownItem>
      </DropdownMenu>
    </Dropdown>
  </div>
</template>

<script>
import "./user.less";
import axios from "axios";
import { mapActions, mapGetters } from "vuex";
export default {
  name: "User",
  props: {
    userAvatar: {
      type: String,
      default: ""
    },
    messageUnreadCount: {
      type: Number,
      default: 0
    }
  },
  data() {
    return {
      alarmSound: undefined
    };
  },
  computed: {
    ...mapGetters(["unreadAlarm", "unreadAlarmCount"]),
    badgeText() {
      if (this.unreadAlarmCount === 0) return "";
      else return "警報";
    }
  },
  watch: {
    unreadAlarmCount: function(newValue, oldValue) {
      if (newValue !== 0) {
        this.alarmSound = new Audio(
          `${axios.defaults.baseURL}static/alarm.mp3`
        );
        this.alarmSound.play();
      }
    }
  },
  methods: {
    ...mapActions(["handleLogOut", "readAlarm"]),
    logout() {
      this.handleLogOut().then(() => {
        this.$router.push({
          name: "login"
        });
      });
    },
    handleClick(name) {
      switch (name) {
        case "logout":
          this.logout();
          break;
        case "readAlarm":
          if (this.alarmSound) this.alarmSound.pause();

          this.readAlarm().then(() => {
            if (this.$router.currentRoute.name !== "alarm") {
              this.$router.push({
                name: "alarm"
              });
            }
          });
          break;
      }
    }
  }
};
</script>
