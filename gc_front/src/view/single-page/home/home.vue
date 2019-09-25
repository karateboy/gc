<template>
  <div>
    <Row :gutter="20">
      <i-col :xs="12" :md="8" :lg="4" key="selector" style="height: 120px;padding-bottom: 10px;">
        <infor-card shadow :color="selector.color" :icon="selector.icon" :icon-size="36">
          <Card :bordered="false" :dis-hover="true" :title="selector.title" :key="selector._id">
            <p>通道:{{selector._id}}</p>
            <p>{{ selector.dp_no}}</p>
          </Card>
        </infor-card>
      </i-col>
      <i-col
        :xs="12"
        :md="8"
        :lg="4"
        v-for="(infor, i) in inforCardData"
        :key="`infor-${i}`"
        style="height: 120px;padding-bottom: 10px;"
      >
        <infor-card shadow :color="infor.color" :icon="infor.icon" :icon-size="36">
          <Card :bordered="false" :dis-hover="true" :title="infor.title">
            <p>{{ infor.text}}</p>
          </Card>
        </infor-card>
      </i-col>
    </Row>
  </div>
</template>

<script>
import InforCard from "_c/info-card";
import { ChartPie, ChartBar } from "_c/charts";
import config from "@/config";
const baseUrl =
  process.env.NODE_ENV === "development"
    ? config.baseUrl.dev
    : config.baseUrl.pro;

import { getRealtimeData, getCurrentMonitor } from "@/api/data";
export default {
  name: "home",
  components: {
    InforCard,
    ChartPie,
    ChartBar
  },
  data() {
    return {
      selector: {
        _id: "default",
        dp_no: "#2",
        icon: "ios-speedometer",
        color: "#ff0000",
        title: "選樣器"
      },
      inforCardData: [],
      pieData: [],
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
    getCurrentMonitor()
      .then(resp => {
        this.selector = Object.assign(
          {
            icon: "ios-speedometer",
            color: "#ff0000",
            title: "選樣器"
          },
          resp.data
        );
      })
      .catch(err => {
        alert(err);
      });

    getRealtimeData()
      .then(resp => {
        this.inforCardData.splice(0, this.inforCardData.length);
        for (let mtData of resp.data.mtDataList) {
          let card = {
            title: mtData.mtName,
            icon: "ios-flask",
            text: mtData.text,
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
