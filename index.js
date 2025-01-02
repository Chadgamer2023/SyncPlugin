require('dotenv').config(); // Load environment variables
const { Client, GatewayIntentBits, Collection } = require('discord.js');
const { MongoClient } = require('mongodb');
const coinflip = require('./coinflip'); // Import the coinflip command
const sync = require('./sync'); // Import the sync command

// Create Discord client with required intents
const client = new Client({
  intents: [
    GatewayIntentBits.Guilds,
    GatewayIntentBits.GuildMessages,
    GatewayIntentBits.MessageContent,
    GatewayIntentBits.DirectMessages,
    GatewayIntentBits.GuildMembers,
  ],
});

// MongoDB Configuration
const mongoURI = process.env.MONGO_URI; // MongoDB connection URI from .env
let mongoClient; // MongoDB client instance

// MongoDB connection function
async function connectMongoDB() {
  try {
    mongoClient = new MongoClient(mongoURI, { useNewUrlParser: true, useUnifiedTopology: true });
    await mongoClient.connect();
    console.log('✅ Connected to MongoDB');
  } catch (error) {
    console.error('❌ Error connecting to MongoDB:', error);
    process.exit(1); // Exit if MongoDB connection fails
  }
}

// Command handling setup
client.commands = new Collection();
client.commands.set(coinflip.data.name, coinflip);
client.commands.set(sync.data.name, sync); // Register the sync command

// Ready event: Bot is online
client.on('ready', () => {
  console.log(`🤖 Bot logged in as ${client.user.tag}`);
});

// Interaction event: Handle commands
client.on('interactionCreate', async (interaction) => {
  if (!interaction.isCommand()) return;

  const { commandName } = interaction;

  // Check if the command exists
  if (client.commands.has(commandName)) {
    const command = client.commands.get(commandName);
    try {
      await command.execute(interaction, mongoClient); // Pass the MongoDB client to the command
    } catch (error) {
      console.error('❌ Error executing command:', error);
      await interaction.reply({ content: 'There was an error executing the command.', ephemeral: true });
    }
  }
});

// Graceful shutdown
process.on('SIGINT', async () => {
  console.log('🔄 Gracefully shutting down...');
  if (mongoClient) {
    await mongoClient.close();
    console.log('✅ MongoDB connection closed.');
  }
  process.exit(0);
});

// Initialize and log into Discord
(async () => {
  await connectMongoDB(); // Connect to MongoDB
  client.login(process.env.DISCORD_TOKEN).then(() => {
    console.log('✅ Bot is starting...');
  }).catch(error => {
    console.error('❌ Error logging in to Discord:', error);
    process.exit(1); // Exit if Discord login fails
  });
})();
