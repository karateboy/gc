<template>
  <div>
    <Row :gutter="20">
      <i-col
        :xs="12"
        :md="8"
        :lg="4"
        v-for="(infor, i) in inforCardData"
        :key="`infor-${i}`"
        style="height: 120px;padding-bottom: 10px;"
      >
        <infor-card shadow :color="infor.color" :icon="infor.icon" :icon-size="36">
          <count-to :end="infor.count" count-class="count-style" />
          <p>{{ infor.title }}</p>
        </infor-card>
      </i-col>
    </Row>
    <Row :gutter="20" style="margin-top: 10px;">
      <i-col :md="24" :lg="8" style="margin-bottom: 20px;">
        <Card shadow>
          <chart-pie style="height: 300px;" :value="pieData" text="氣體組成" :key="pieKey"></chart-pie>
        </Card>
      </i-col>
    </Row>
  </div>
</template>

<script>
import InforCard from "_c/info-card";
import CountTo from "_c/count-to";
import { ChartPie, ChartBar } from "_c/charts";
import Example from "./example.vue";
import config from "@/config";
const baseUrl =
  process.env.NODE_ENV === "development"
    ? config.baseUrl.dev
    : config.baseUrl.pro;

import { getRealtimeData } from "@/api/data";
export default {
  name: "home",
  components: {
    InforCard,
    CountTo,
    ChartPie,
    ChartBar,
    Example
  },
  data() {
    return {
      inforCardData: [
        {
          title: "新增用户",
          icon: "md-person-add",
          count: 803,
          color: "#2d8cf0"
        },
        { title: "累计点击", icon: "md-locate", count: 232, color: "#19be6b" },
        {
          title: "新增问答",
          icon: "md-help-circle",
          count: 142,
          color: "#ff9900"
        },
        { title: "分享统计", icon: "md-share", count: 657, color: "#ed3f14" },
        {
          title: "新增互动",
          icon: "md-chatbubbles",
          count: 12,
          color: "#E46CBB"
        },
        { title: "新增页面", icon: "md-map", count: 14, color: "#9A66E4" }
      ],
      pieData: [
      ],
      pieKey: 0,
      barData: {
        Mon: 13253,
        Tue: 34235,
        Wed: 26321,
        Thu: 12340,
        Fri: 24643,
        Sat: 1322,
        Sun: 1324
      }
    };
  },
  mounted() {
    getRealtimeData()
      .then(resp => {
        console.log(resp.data);
        this.inforCardData.splice(0, this.inforCardData.length);
        this.pieData.splice(0, this.pieData.length);
        for (let mtData of resp.data.mtDataList) {
          let card = {
            title: mtData.mtName,
            icon: "ios-flask",
            count: mtData.value,
            color: "#ff9900"
          };
          this.inforCardData.push(card);

          let pieSlice = {
            value: mtData.value,
            name: mtData.mtName
          };
          this.pieData.push(pieSlice);
        }
        this.pieKey++;
      })
      .catch(err => {
        alert(err);
      });
  }
};
</script>

<style lang="less">
.count-style {
  font-size: 50px;
}
</style>
