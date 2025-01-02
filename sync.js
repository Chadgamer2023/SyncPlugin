const { MongoClient } = require('mongodb');

const uri = 'mongodbhere'; // Replace with your MongoDB connection string
const client = new MongoClient(uri);

let SyncedPlayers; // Declare globally this is the collection we will use from mongodb feel free to change the name

async function connectToDatabase() {
  try {
    await client.connect();
    const database = client.db('Players'); // Replace with your database name
    SyncedPlayers = database.collection('SyncedPlayers'); // Replace with your collection name
    console.log('Connected to MongoDB and initialized SyncedPlayers collection.');
  } catch (error) {
    console.error('Failed to connect to MongoDB:', error);
  }
}

connectToDatabase();

async function syncCommand(interaction, mongoClient) {
  try {
    // Acknowledge the interaction immediately
    await interaction.deferReply({ ephemeral: true });

    const code = interaction.options.getString('code'); // The unique sync code
    const username = interaction.options.getString('username'); // The Minecraft username

    console.log('Command Options:', interaction.options.data);
    console.log('Code:', code);
    console.log('Minecraft Username:', username);

    if (!code || !username) {
      await interaction.editReply({
        content: 'Please provide both a code and a Minecraft username.',
      });
      return;
    }

    const database = mongoClient.db('Players'); // Replace with your database name
    const SyncedPlayers = database.collection('SyncedPlayers'); // Replace with your collection name

    // Validate the code and username in MongoDB
    const user = await SyncedPlayers.findOne({
      syncCode: String(code),
      username: String(username),
    });

    if (!user) {
      console.log(`No user found with syncCode: ${code} and username: ${username}`);
      await interaction.editReply({
        content: 'Invalid sync code or Minecraft username. Please try again.',
      });
      return;
    }

    if (user.discordId) {
      await interaction.editReply({
        content: 'This account is already synced with a Discord user.',
      });
      return;
    }

    await SyncedPlayers.updateOne(
      { syncCode: String(code), username: String(username) },
      { $set: { discordId: interaction.user.id } }
    );

    console.log(`Successfully synced Minecraft username: ${username} with Discord user ID: ${interaction.user.id}`);

    await interaction.editReply({
      content: 'Account synced successfully.',
    });
  } catch (error) {
    console.error('Error in syncCommand:', error);

    if (!interaction.replied && !interaction.deferred) {
      await interaction.reply({
        content: 'An error occurred while processing your request. Please try again later.',
        ephemeral: true,
      });
    } else {
      await interaction.followUp({
        content: 'An error occurred while processing your request. Please try again later.',
      });
    }
  }
}

module.exports = {
  data: {
    name: 'sync',
  },
  execute: syncCommand,
};
