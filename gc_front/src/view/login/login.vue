<style lang="less">
@import "./login.less";
</style>

<template>
  <div class="login">
    <div class="login-con">
      <Card icon="log-in" title="使用者登入" :bordered="false">
        <div class="form-con">
          <login-form @on-success-valid="handleSubmit"></login-form>
          <p class="login-tip">公用帳號user</p>
        </div>
      </Card>
    </div>
  </div>
</template>

<script>
import LoginForm from "_c/login-form";
import { mapActions } from "vuex";
export default {
  components: {
    LoginForm
  },
  methods: {
    ...mapActions(["handleLogin", "getUserInfo"]),
    handleSubmit({ userName, password }) {
      this.handleLogin({ userName, password }).then(
        ret => {
          console.log(ret);
          const resp = ret.data;
          if (resp.ok) {
            this.getUserInfo().then(res => {
              this.$router.push({
                name: this.$config.homeName
              });
            });
          } else {
            this.$Message.error(resp.msg);
          }
        },
        err => {}
      );
    }
  }
};
</script>

<style>
</style>
