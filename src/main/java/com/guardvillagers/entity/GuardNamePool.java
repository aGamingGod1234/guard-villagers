package com.guardvillagers.entity;

import net.minecraft.util.math.random.Random;

import java.util.List;

public final class GuardNamePool {
	public static final List<String> REGULAR_NAMES = List.of(
		"Aaliyah", "Aaron", "Abigail", "Adam", "Aisha", "Akira", "Alan", "Alana", "Albert", "Alejandro",
		"Alex", "Alexa", "Alexander", "Alexis", "Ali", "Alicia", "Alina", "Alison", "Alyssa", "Amanda",
		"Amara", "Amelia", "Amina", "Amir", "Amy", "Ananya", "Andre", "Andrea", "Andrew", "Angela",
		"Anita", "Ann", "Anna", "Anne", "Anthony", "Aria", "Ariana", "Arianna", "Arjun", "Arthur",
		"Ash", "Ashley", "Ashton", "Astrid", "Athena", "Aubrey", "Austin", "Ava", "Avery", "Bailey",
		"Barbara", "Beatrice", "Becky", "Bella", "Ben", "Benjamin", "Bennett", "Bianca", "Blake", "Brandon",
		"Brenda", "Brian", "Brianna", "Brittany", "Brooke", "Bryan", "Caleb", "Camila", "Camille", "Carlos",
		"Carmen", "Caroline", "Carter", "Casey", "Cassandra", "Catherine", "Cecilia", "Celeste", "Chad", "Charlotte",
		"Chloe", "Chris", "Christian", "Christina", "Claire", "Clara", "Cole", "Colin", "Connor", "Cooper",
		"Courtney", "Crystal", "Cynthia", "Daisy", "Dakota", "Daniel", "Daniela", "Danielle", "Daphne", "David",
		"Dean", "Deborah", "Delilah", "Derek", "Diana", "Diego", "Dominic", "Donna", "Dylan", "Eden",
		"Edgar", "Eduardo", "Eleanor", "Elena", "Eli", "Eliana", "Elijah", "Elisa", "Elise", "Elizabeth",
		"Ella", "Ellie", "Elliot", "Emily", "Emma", "Enzo", "Eric", "Erica", "Erik", "Erin",
		"Ethan", "Eva", "Evan", "Evelyn", "Faith", "Fatima", "Felix", "Fiona", "Florence", "Frances",
		"Francis", "Frank", "Gabriel", "Gabriela", "Gabrielle", "Gavin", "Gemma", "George", "Georgia", "Gianna",
		"Giselle", "Grace", "Graham", "Grant", "Greg", "Hailey", "Hana", "Hannah", "Harper", "Harry",
		"Hazel", "Heidi", "Helen", "Henry", "Holly", "Hope", "Hudson", "Hugo", "Hunter", "Ibrahim",
		"Imani", "Irene", "Isaac", "Isabel", "Isabella", "Isla", "Ivan", "Ivy", "Jack", "Jackson",
		"Jade", "Jamal", "James", "Jamie", "Jane", "Jasmine", "Jason", "Javier", "Jay", "Jean",
		"Jenna", "Jennifer", "Jeremy", "Jerome", "Jerry", "Jesse", "Jessica", "Jill", "Joan", "Joanna",
		"Jocelyn", "Joel", "John", "Johnny", "Jonah", "Jonathan", "Jordan", "Jose", "Joseph", "Joshua",
		"Joy", "Juan", "Judith", "Julia", "Julian", "Julie", "June", "Justin", "Kai", "Kaitlyn",
		"Karen", "Karina", "Katherine", "Kathryn", "Kayla", "Keith", "Kelly", "Ken", "Kenneth", "Kevin",
		"Kiara", "Kim", "Kimberly", "Kira", "Kristen", "Kyle", "Lana", "Lara", "Laura", "Lauren",
		"Layla", "Leah", "Leo", "Leon", "Leona", "Leonardo", "Leslie", "Liam", "Lila", "Lillian",
		"Lily", "Linda", "Lindsay", "Linh", "Logan", "Lola", "Lorenzo", "Louis", "Lucas", "Lucy",
		"Luis", "Luna", "Lydia", "Mackenzie", "Madeline", "Madison", "Mae", "Maya", "Megan", "Melanie",
		"Melissa", "Mia", "Micah", "Michael", "Michelle", "Mila", "Miles", "Mina", "Mira", "Molly",
		"Monica", "Morgan", "Muhammad", "Naomi", "Natalie", "Nathan", "Nathaniel", "Neil", "Nia", "Nicholas",
		"Nicole", "Nina", "Noah", "Nora", "Nova", "Olivia", "Omar", "Oscar", "Owen", "Paige",
		"Pamela", "Parker", "Patricia", "Paul", "Paula", "Payton", "Penelope", "Peter", "Philip", "Phoebe",
		"Piper", "Priya", "Quentin", "Rachel", "Rafael", "Rahul", "Raul", "Ray", "Rebecca", "Reese",
		"Renee", "Rhett", "Richard", "Riley", "Rita", "Robert", "Robin", "Rosa", "Rose", "Ruby",
		"Ryan", "Sabrina", "Sage", "Samantha", "Samir", "Samuel", "Sara", "Sarah", "Savannah", "Scarlett",
		"Scott", "Sean", "Selena", "Serena", "Seth", "Shane", "Shawn", "Sheila", "Sienna", "Sofia",
		"Sophia", "Sophie", "Spencer", "Stella", "Stephanie", "Stephen", "Steve", "Summer", "Susan", "Sydney",
		"Sylvia", "Talia", "Tamara", "Tania", "Tara", "Taylor", "Teresa", "Tessa", "Theo", "Thomas",
		"Tiffany", "Tim", "Tina", "Tobias", "Tracy", "Trent", "Trevor", "Tristan", "Tyler", "Uma",
		"Valerie", "Vanessa", "Veronica", "Victor", "Victoria", "Violet", "Vivian", "Walter", "Wanda", "Wesley",
		"Whitney", "Will", "William", "Willow", "Wyatt", "Xander", "Yara", "Yasmin", "Yasmine", "Yvonne",
		"Zach", "Zane", "Zara", "Zoe", "Zoey"
	);

	private GuardNamePool() {
	}

	public static String randomRegularName(Random random) {
		if (REGULAR_NAMES.isEmpty()) {
			return "Guard";
		}
		return REGULAR_NAMES.get(random.nextInt(REGULAR_NAMES.size()));
	}
}
