<template>
  <div id="container"></div>
</template>

<script lang="ts">
// Instantiate a deck with all cards
let deck = Deck();

// display it in a html container
let $container = document.getElementById('container');
deck.mount($container);
</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
h3 {
  margin: 40px 0 0;
}
ul {
  list-style-type: none;
  padding: 0;
}
li {
  display: inline-block;
  margin: 0 10px;
}
a {
  color: #42b983;
}
</style>
