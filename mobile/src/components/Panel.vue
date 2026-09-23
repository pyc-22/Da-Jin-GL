<template><section class="panel"><header class="panel-head"><h2>{{ title }}</h2><slot name="actions" /></header><slot /></section></template><script setup>defineProps({title:String})</script>
